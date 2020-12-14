package au.org.ala.profile

import au.org.ala.names.search.HomonymException
import au.org.ala.profile.util.CloneAndDraftUtil
import au.org.ala.profile.util.ImageOption
import au.org.ala.profile.util.StorageExtension
import au.org.ala.profile.util.Utils
import au.org.ala.web.AuthService
import com.google.common.base.Stopwatch
import groovy.transform.stc.ClosureParams
import groovy.transform.stc.SimpleType
import org.apache.commons.lang3.StringUtils
import org.codehaus.groovy.grails.commons.DomainClassArtefactHandler
import org.springframework.transaction.annotation.Transactional
import org.springframework.web.multipart.MultipartFile
import org.springframework.web.multipart.commons.CommonsMultipartFile

import java.text.SimpleDateFormat
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

@Transactional
class ProfileService extends BaseDataAccessService {

    VocabService vocabService
    NameService nameService
    AuthService authService
    BieService bieService
    DoiService doiService
    AttachmentService attachmentService
    SearchService searchService
    MasterListService masterListService
    def grailsApplication

    Profile decorateProfile(Profile profile, boolean latest, boolean checkForChildren) {
        Stopwatch sw = new Stopwatch().start()

        def set = masterListService.getCombinedLowerCaseNamesListForUser(profile.opus)?.toSet()

        if (profile.classification) {
            def classifications = profile.draft && latest ? profile.draft.classification : profile.classification
            classifications.each { cl ->
                // only count children if requested to
                if (checkForChildren) {
                    cl.hasChildren = searchService.hasDescendantsByClassificationAndRank(cl.rank?.toLowerCase(), cl.name, profile.opus, true)
                }

                Profile relatedProfile = cl.guid? Profile.findByGuidAndOpusAndArchivedDateIsNull(cl.guid, profile.opus) : null
                if (!relatedProfile) {
                    relatedProfile = Profile.findByScientificNameAndOpusAndArchivedDateIsNull(cl.name, profile.opus)
                }

                if (set == null || (relatedProfile && set.contains(relatedProfile.scientificNameLower))) {
                    cl.profileId = relatedProfile?.uuid
                    cl.profileName = relatedProfile?.scientificName
                } else {
                    cl.profileId = null
                    cl.profileName = null
                }

            }

            log.debug("decorateProfile() - Get classification childCounts (Check for children $checkForChildren) profile ids and profileNames: $sw")
            sw.reset().start()

        }

        profile
    }

    Map checkName(String opusId, String name) {
        Map result = [providedName: name, providedNameDuplicates: [], matchedName: [:], matchedNameDuplicates: []]

        Opus opus = Opus.findByUuid(opusId)
        checkState opus

        // 1. search for the name as it was provided to check for duplicates, searching as both the scientific name and the full name
        List providedScientificNameDuplicates = findByName(name, opus)
        if (providedScientificNameDuplicates) {
            result.providedNameDuplicates = providedScientificNameDuplicates.collect {
                [profileId: it.uuid, scientificName: it.scientificName, fullName: it.fullName, nameAuthor: it.nameAuthor, rank: it.rank]
            }
        }

        // 2. attempt to match the name
        try {
            Map matchedName = nameService.matchName(name)
            if (matchedName) {
                result.matchedName = [scientificName: matchedName.scientificName, fullName: matchedName.fullName, nameAuthor: matchedName.nameAuthor, guid: matchedName.guid, rank: matchedName.rank]

                List matchedScientificNameDuplicates = findByName(result.matchedName.scientificName, opus)
                if (matchedScientificNameDuplicates) {
                    result.matchedNameDuplicates = matchedScientificNameDuplicates.collect {
                        [profileId: it.uuid, scientificName: it.scientificName, fullName: it.fullName, nameAuthor: it.nameAuthor, rank: it.rank]
                    }
                }
                List matchedFullNameDuplicates = findByName(result.matchedName.fullName, opus)
                if (matchedFullNameDuplicates) {
                    result.matchedNameDuplicates = matchedFullNameDuplicates.collect {
                        [profileId: it.uuid, scientificName: it.scientificName, fullName: it.fullName, nameAuthor: it.nameAuthor, rank: it.rank]
                    }
                }
            }
        } catch (HomonymException he) {
            result.matchedNameDuplicates = he.results.findResults {
                if (it.rank) {
                    def sciName = it.rankClassification.scientificName
                    def author = it.rankClassification.authorship
                    [profileId: it.id, scientificName: sciName, fullName: "${sciName} ${author}", nameAuthor: author, rank: it.rank?.rank, kingdom: it.rankClassification.kingdom]
                }
            }
        } catch (Exception e) {
            log.warn e.message, e
        }

        result
    }

    private List<Profile> findByName(String name, Opus opus) {
        Profile.withCriteria {
            eq "opus", opus

            or {
                ilike "scientificName", name
                ilike "fullName", name
                ilike "draft.scientificName", name
                ilike "draft.fullName", name
            }

            isNull "archivedDate" // ignore archived profiles during name checks
        }
    }

    Profile createProfile(String opusId, Map json) {
        createProfile(opusId, json) { profile ->
            if (!profile.profileStatus) {
                profile.profileStatus = Profile.STATUS_PARTIAL
            }
        }
    }

    /**
     * Create a profile in a given opus, with a given set of initial fields in the json Map.  The unsaved
     * profile is then passed to the populateProfile closure to allow the caller the opportunity to make
     * changes to the new profile before it is saved and autoDraftProfiles is applied.
     * @param opusId The Opus UUID
     * @param json The map of initial Profile properties
     * @param populateProfile A closure that takes a single Profile as the argument and can mutate the profile before saving.
     * @return The saved profile or null if the profile was not saved correctly.
     */
    Profile createProfile(String opusId, Map json,
                          @ClosureParams(value = SimpleType, options = "au.org.ala.profile.Profile") Closure<?> populateProfile) {
        checkArgument opusId
        checkArgument json

        Opus opus = Opus.findByUuid(opusId)

        checkState opus

        Profile profile = new Profile(json)
        profile.opus = opus

        Map matchedName = nameService.matchName(json.scientificName, [:], json.manuallyMatchedGuid ?: null)

        updateNameDetails(profile, matchedName, json.scientificName, json.manualHierarchy ?: [])

        if (authService.getUserId()) {
            Term term = vocabService.getOrCreateTerm("Author", opus.authorshipVocabUuid)
            profile.authorship = [new Authorship(category: term, text: authService.getUserForUserId(authService.getUserId()).displayName)]
        }

        if (json.manuallyMatchedGuid) {
            profile.manuallyMatchedName = true
        } else {
            profile.manuallyMatchedName = false
        }

        if (populateProfile) {
            populateProfile(profile)
        }

        boolean success = save profile

        if (!success) {
            profile = null
        } else if (opus.autoDraftProfiles) {
            toggleDraftMode(profile.uuid)
        }

        profile
    }

    Profile duplicateProfile(String opusId, Profile sourceProfile, Map json) {
        checkArgument sourceProfile

        createProfile(opusId, json) { profile ->
            // certain things cannot be cloned, such as ids, profile-specific properties like image config and the
            // occurrence query, and attachments
            profile.specimenIds = sourceProfile.specimenIds?.collect()
            profile.authorship = sourceProfile.authorship?.collect { CloneAndDraftUtil.cloneAuthorship(it) }
            profile.profileSettings = new ProfileSettings(autoFormatProfileName: json.profileSettings?.autoFormatProfileName, formattedNameText: json.profileSettings?.formattedNameText)
            //new ProfileSettings(formatNameWithNormalText: sourceProfile?.profileSettings?.formatNameWithNormalText)

            // For a draft profile
            if (sourceProfile.draft) {
                profile.uuid = UUID.randomUUID().toString()
                profile.draft = new DraftProfile()
                profile.draft.uuid = profile.uuid
                profile.draft.scientificName = profile.scientificName
                profile.draft.scientificNameLower = profile.scientificNameLower

                // attributes
                def uuidMap = [:]
                // copy published ones
                profile.draft.attributes = sourceProfile.attributes?.collect {
                    Attribute newAttribute = CloneAndDraftUtil.cloneAttribute(it, false)
                    newAttribute.uuid = UUID.randomUUID().toString()
                    uuidMap.put(it.uuid, newAttribute.uuid)
                    newAttribute
                }?.toList()
                // update values if any
                sourceProfile.draft.attributes?.each {
                    String uuid = it.uuid
                    Attribute existing = profile.draft.attributes.find { it.uuid == uuidMap[uuid] }
                    if (existing) {
                        existing.title = it.title
                        existing.text = it.text
                    } else {
                        Attribute newAttribute = CloneAndDraftUtil.cloneAttribute(it, false)
                        newAttribute.uuid = UUID.randomUUID().toString()
                        profile.draft.attributes << newAttribute
                    }
                }

                // links
                profile.draft.links = sourceProfile.links?.collect {
                    Link newLink = CloneAndDraftUtil.cloneLink(it, false)
                    newLink.uuid = UUID.randomUUID().toString()
                    uuidMap.put(it.uuid, newLink.uuid)

                }?.toList()
                sourceProfile.draft.links?.each {
                    String uuid = it.uuid
                    Link existing = profile.draft.links.find { it.uuid == uuidMap[uuid] }
                    if (existing) {
                        existing.title = it.title
                        existing.url = it.url
                        existing.description = it.description
                    } else {
                        Link newLink = CloneAndDraftUtil.cloneLink(it, false)
                        newLink.uuid = UUID.randomUUID().toString()
                        profile.draft.links << newLink
                    }
                }

                // Biodiversity Heritage Library references
                profile.draft.bhlLinks = sourceProfile.bhlLinks?.collect {
                    Link newLink = CloneAndDraftUtil.cloneLink(it, false)
                    newLink.uuid = UUID.randomUUID().toString()
                    uuidMap.put(it.uuid, newLink.uuid)
                }?.toList()
                sourceProfile.draft.bhlLinks?.each {
                    String uuid = it.uuid
                    Link existing = profile.draft.bhlLinks.find { it.uuid == uuidMap[uuid] }
                    if (existing) {
                        existing.title = it.title
                        existing.url = it.url
                        existing.description = it.description
                    } else {
                        Link newLink = CloneAndDraftUtil.cloneLink(it, false)
                        newLink.uuid = UUID.randomUUID().toString()
                        profile.draft.bhlLinks << newLink
                    }
                }

                // Bibliography
                profile.draft.bibliography = sourceProfile.bibliography?.collect {
                    Bibliography newBb = CloneAndDraftUtil.cloneBibliography(it, false)
                    newBb.uuid = UUID.randomUUID().toString()
                    uuidMap.put(it.uuid, newBb.uuid)
                }?.toList()
                sourceProfile.draft.bibliography?.each {
                    String uuid = it.uuid
                    Bibliography existing = profile.draft.bibliography.find { it.uuid == uuidMap[uuid] }
                    if (existing) {
                        existing.text = it.text
                        existing.order = it.order
                    } else {
                        Bibliography newBb = CloneAndDraftUtil.cloneBibliography(it, false)
                        newBb.uuid = UUID.randomUUID().toString()
                        profile.draft.bibliography << newBb
                    }
                }

            } else {
                profile.attributes = sourceProfile.attributes?.collect {
                    Attribute newAttribute = CloneAndDraftUtil.cloneAttribute(it, false)
                    newAttribute.uuid = UUID.randomUUID().toString()
                    newAttribute
                }?.toSet()
                profile.attributes.each {
                    profile.addToAttributes(it)
                }

                profile.links = sourceProfile.links?.collect { CloneAndDraftUtil.cloneLink(it, false) }
                profile.links?.each {
                    it.uuid = UUID.randomUUID().toString()
                }
                profile.bhlLinks = sourceProfile.bhlLinks?.collect { CloneAndDraftUtil.cloneLink(it, false) }
                profile.bhlLinks?.each {
                    it.uuid = UUID.randomUUID().toString()
                }
                profile.bibliography = sourceProfile.bibliography?.collect { CloneAndDraftUtil.cloneBibliography(it, false) }
                profile.bibliography?.each {
                    it.uuid = UUID.randomUUID().toString()
                }
            }
        }
    }

    private void updateNameDetails(profile, Map matchedName, String providedName, List manualHierarchy) {
        if (matchedName) {
            if (providedName.equalsIgnoreCase(matchedName.fullName) || providedName.equalsIgnoreCase(matchedName.scientificName)) {
                profile.scientificName = matchedName.scientificName
                profile.fullName = matchedName.fullName
                profile.nameAuthor = matchedName.nameAuthor
            } else {
                profile.scientificName = providedName
                profile.fullName = providedName
                profile.nameAuthor = null
            }
            profile.matchedName = new Name(matchedName)
            profile.guid = matchedName.guid
        } else {
            profile.scientificName = providedName
            profile.fullName = providedName
            profile.matchedName = null
            profile.nameAuthor = null
            profile.guid = null
            profile.classification = []
            profile.rank = null
        }

        populateTaxonHierarchy(profile, manualHierarchy)

        // try to match the name against the NSL. If we get a match, and there is currently no name author, use the author from the NSL match
        Map matchedNSLName = nameService.matchNSLName(providedName, profile.rank)
        if (matchedNSLName) {
            profile.nslNameIdentifier = matchedNSLName.nslIdentifier
            profile.nslProtologue = matchedNSLName.nslProtologue
            if (!profile.nameAuthor) {
                profile.nameAuthor = matchedNSLName.nameAuthor
            }
        } else {
            profile.nslNameIdentifier = null
            profile.nslNomenclatureIdentifier = null
            profile.nslProtologue = null
        }
    }

    void renameProfile(String profileId, Map json) {
        checkArgument profileId
        checkArgument json

        def profile = Profile.findByUuid(profileId)
        checkState profile

        if (json.newName) {
            Map matchedName = nameService.matchName(json.newName, [:], json.manuallyMatchedGuid ?: null)

            updateNameDetails(profileOrDraft(profile), matchedName, json.newName, json.manualHierarchy ?: [])
        }

        if (json.clearMatch?.booleanValue()) {
            updateNameDetails(profileOrDraft(profile), null, profileOrDraft(profile).scientificName, json.manualHierarchy ?: [])
        }

        if (json.manuallyMatchedGuid) {
            profile.manuallyMatchedName = true
        } else {
            profile.manuallyMatchedName = false
        }

        boolean success = save profile

        if (!success) {
            profile = null
        }

        profile
    }

    void populateTaxonHierarchy(profile, List manualHierarchy = []) {
        if (!profile) {
            return
        }

        if (profile.guid) {
            List classificationJson = bieService.getClassification(profile.guid)

            if (classificationJson) {
                profile.classification = classificationJson.collect {
                    // the classifications returned from the BIE service are in descending order, so the last one will be
                    // the rank for the profile
                    profile.rank = it.rank

                    new Classification(rank: it.rank, guid: it.guid, name: it.scientificName)
                }

                Map speciesProfile = bieService.getSpeciesProfile(profile.guid)
                profile.taxonomyTree = speciesProfile?.taxonConcept?.infoSourceName
                profile.manualClassification = false
            } else {
                log.info("Unable to find species classification for ${profile.scientificName}, with GUID ${profile.guid}")
            }
        } else if (manualHierarchy) {
            // If there is a matched name, then the classification (aka taxonomic hierarchy) will always be derived from
            // the matched name.
            // If there is no matched name, then the user could have specified the hierarchy. In that case, the hierarchy
            // will be in the opposite order (lowest rank first since the user enters the parent of the profile - i.e.
            // they work up the hierarchy, not down - whereas we need to store in highest rank first).
            // The manual hierarchy could have some unknown items in it, and it could have 1 recognised name.
            // If we have a recognised name, then the hierarchy from that point UP needs to be derived from the name.
            profile.classification = []

            // The profile's rank will be stored in the first element of the manually entered hierarchy
            profile.rank = manualHierarchy[0].rank

            manualHierarchy.reverse().each { manualHierarchyItem ->
                // The manually selected hierarchy item could be one of (mutually exclusive):
                // 1. A name from the ALA name index, in which case the guid will be the LSID/GUID of the Name; or
                // 2. Another profile, in which case the guid will be the UUID of the parent profile; or
                // 3. Another unrecognised name that does not have a profile, in which case the guid will be null

                if (manualHierarchyItem.guid && Utils.isUuid(manualHierarchyItem.guid)) {
                    Profile parent = Profile.findByUuid(manualHierarchyItem.guid)
                    parent?.classification?.each {
                        profile.classification << new Classification(rank: it.rank ?: "", guid: it.guid, name: it.name)
                    }
                } else if (manualHierarchyItem.guid && !Utils.isUuid(manualHierarchyItem.guid)) {
                    List derivedHierarchy = bieService.getClassification(manualHierarchyItem.guid)
                    derivedHierarchy.each {
                        profile.classification << new Classification(rank: it.rank ?: "", guid: it.guid, name: it.scientificName)
                    }
                } else {
                    profile.classification << new Classification(name: manualHierarchyItem.name, guid: manualHierarchyItem.guid, rank: manualHierarchyItem.rank ?: "")
                }
            }

            profile.manualClassification = true
        }
    }

    Profile archiveProfile(String profileId, String archiveComment) {
        checkArgument profileId
        checkArgument archiveComment

        Profile profile = Profile.findByUuid(profileId)
        checkState profile

        profile.archiveComment = archiveComment
        profile.archivedBy = authService.getUserForUserId(authService.getUserId()).displayName
        profile.archivedDate = new Date()
        profile.archivedWithName = profile.scientificName
        profile.scientificName = "${profile.scientificName} (Archived ${new SimpleDateFormat('dd/MM/yyyy H:mm a').format(profile.archivedDate)})"

        save profile

        profile
    }

    Profile restoreArchivedProfile(String profileId, String newName = null) {
        checkArgument profileId

        Profile profile = Profile.findByUuid(profileId)
        checkState profile

        if (newName && Profile.findByOpusAndScientificName(profile.opus, newName)) {
            throw new IllegalStateException("A profile already exists with the name ${newName}")
        } else if (!newName && Profile.findByOpusAndScientificName(profile.opus, profile.archivedWithName)) {
            throw new IllegalStateException("A profile already exists with the name ${profile.archivedWithName}. Provide a new unique name.")
        }

        profile.archiveComment = null
        profile.archivedBy = null
        profile.archivedDate = null
        profile.scientificName = newName ?: profile.archivedWithName
        profile.archivedWithName = null

        save profile

        profile
    }

    boolean deleteProfile(String profileId) {
        checkArgument profileId

        Profile profile = Profile.findByUuid(profileId)
        checkState profile

        if (profile.publications) {
            throw new IllegalStateException("Profiles with published versions cannot be deleted. Use the archive option instead.")
        }

        delete profile
    }

    boolean discardDraftChanges(String profileId) {
        checkArgument profileId

        Profile profile = Profile.findByUuid(profileId)
        checkState profile

        if (profile.draft) {
            // clean up any draft attachment files
            List existingIds = profile.attachments?.collect { it.uuid } ?: []

            profile.draft.attachments?.each { draftAttachment ->
                if (!existingIds.contains(draftAttachment.uuid)) {
                    attachmentService.deleteAttachment(profile.opus.uuid, profile.uuid, draftAttachment.uuid, Utils.getFileExtension(draftAttachment.filename))
                }
            }

            profile.draft = null

            save profile
        }
    }

    boolean toggleDraftMode(String profileId, boolean publish = false) {
        checkArgument profileId

        Profile profile = Profile.findByUuid(profileId)
        checkState profile

        if (profile.draft && publish) {
            // delete files for attachments that were removed during the draft stage
            profile.attachments?.each { attachment ->
                if (profile.draft.attachments?.find { it.uuid == attachment.uuid } == null) {
                    attachmentService.deleteAttachment(profile.opus.uuid, profile.uuid, attachment.uuid, Utils.getFileExtension(attachment.filename))
                }
            }

            CloneAndDraftUtil.updateProfileFromDraft(profile)

            Set<Attribute> attributesToDelete = profile.attributes.findAll {
                String uuid = it.uuid
                profile.draft.attributes.find { it.uuid == uuid } == null
            }

            profile.draft = null

            attributesToDelete.each {
                profile.attributes.remove(it)
                delete it
            }


            save profile
        } else {
            profile.draft = profile.draft ?: CloneAndDraftUtil.createDraft(profile)
            profile.draft.createdBy = authService.getUserForUserId(authService.getUserId()).displayName

            save profile
        }
    }

    def profileOrDraft(Profile profile) {
        profile.draft ?: profile
    }

    Profile updateProfile(String profileId, Map json) {
        checkArgument profileId
        checkArgument json

        Profile profile = Profile.findByUuid(profileId)
        checkState profile

        if (json.containsKey("nslNomenclatureIdentifier")) {
            profileOrDraft(profile).nslNomenclatureIdentifier = json.nslNomenclatureIdentifier ?: null
        }

        if (json.containsKey("showLinkedOpusAttributes")) {
            profileOrDraft(profile).showLinkedOpusAttributes = json.showLinkedOpusAttributes as Boolean
        }

        if (json.containsKey("occurrenceQuery")) {
            profileOrDraft(profile).occurrenceQuery = json.occurrenceQuery ?: null
        }

        if (json.containsKey("isCustomMapConfig") && json.isCustomMapConfig) {
            profileOrDraft(profile).isCustomMapConfig = true
            profileOrDraft(profile).occurrenceQuery = json.occurrenceQuery ?: null
        } else {
            profileOrDraft(profile).isCustomMapConfig = false;
            profileOrDraft(profile).occurrenceQuery = null
        }

        saveImages(profile, json, true)

        saveSpecimens(profile, json, true)

        saveBibliography(profile, json, true)

        saveAuthorship(profile, json, true)

        saveProfileSettings(profile, json, true)

        boolean success = save profile

        if (!success) {
            profile = null
        }

        profile
    }

    boolean saveSpecimens(Profile profile, Map json, boolean deferSave = false) {
        checkArgument profile
        checkArgument json

        if (json.containsKey("specimenIds")) {
            profileOrDraft(profile).specimenIds = []
            if (json.specimenIds) {
                profileOrDraft(profile).specimenIds.addAll(json.specimenIds)
            }

            if (!deferSave) {
                save profile
            }
        }
    }

    boolean saveAuthorship(Profile profile, Map json, boolean deferSave = false) {
        if (json.containsKey("authorship")) {
            profileOrDraft(profile).authorship = []
            if (json.authorship) {
                profileOrDraft(profile).authorship = json.authorship.collect {
                    Term term = vocabService.getOrCreateTerm(it.category,
                            profile.opus.authorshipVocabUuid)
                    new Authorship(category: term, text: it.text)
                }
            }

            if (!deferSave) {
                save profile
            }
        }
    }

    boolean saveAuthorship(String profileId, Map json) {
        checkArgument profileId
        checkArgument json

        Profile profile = Profile.findByUuid(profileId)
        checkState profile

        saveAuthorship(profile, json)
    }

    boolean saveProfileSettings(Profile profile, Map json, boolean deferSave = false) {
        if (json.containsKey("profileSettings")) {
            if (json.profileSettings) {
                if (!profileOrDraft(profile).profileSettings) {
                    profileOrDraft(profile).profileSettings =
                            new ProfileSettings(autoFormatProfileName: json.profileSettings?.autoFormatProfileName, formattedNameText: json.profileSettings?.formattedNameText)
                } else {
                    profileOrDraft(profile).profileSettings.autoFormatProfileName = json.profileSettings?.autoFormatProfileName
                    profileOrDraft(profile).profileSettings.formattedNameText = json.profileSettings?.formattedNameText
                }
            }
            if (!deferSave) {
                save profile
            }
        }
    }

    boolean saveProfileSettings(String profileId, Map json) {
        checkArgument profileId
        checkArgument json

        Profile profile = Profile.findByUuid(profileId)
        checkState profile

        saveProfileSettings(profile, json)
    }

    boolean saveImages(Profile profile, Map json, boolean deferSave = false) {
        checkArgument profile
        checkArgument json

        def profileOrDraft = profileOrDraft(profile)

        if (json.containsKey('primaryImage')) {
            profileOrDraft.primaryImage = json.primaryImage ?: null
        }

        if (json.containsKey("imageSettings")) {
            profileOrDraft.imageSettings = [:]
            if (json.imageSettings) {
                profileOrDraft.imageSettings = json.imageSettings.collectEntries {
                    ImageOption imageDisplayOption = it.displayOption ?
                            ImageOption.byName(it.displayOption, profile.opus.approvedImageOption) :
                            profile.opus.approvedImageOption
                    if (imageDisplayOption == profile.opus.approvedImageOption) {
                        imageDisplayOption = null
                    }

                    [(it.imageId): new ImageSettings(imageDisplayOption: imageDisplayOption, caption: it.caption ?: '')]
                }
            }
        }

        if (!deferSave) {
            save profile
        }
    }

    boolean recordStagedImage(String profileId, Map json) {
        checkArgument profileId
        checkArgument json

        Profile profile = Profile.findByUuid(profileId)
        checkState profile
        checkState profile.draft
        // can only stage images for a draft profile - otherwise images are to be automatically updated

        boolean success = recordImage(profile.draft.stagedImages, json)

        if (success) {
            if (json.action == "delete") {
                profile.imageDisplayOptions?.remove(json.imageId)
            }
            success = save profile
        } else {
            log.error "Failed to record image (prior to saving the profile)"
        }

        success
    }

    boolean recordPrivateImage(String profileId, Map json) {
        checkArgument profileId
        checkArgument json

        Profile profile = Profile.findByUuid(profileId)
        checkState profile

        boolean success = recordImage(profileOrDraft(profile).privateImages, json)

        if (success) {
            if (json.action == "delete") {
                profile.imageDisplayOptions?.remove(json.imageId)
            }
            success = save profile
        } else {
            log.error "Failed to record image (prior to saving the profile)"
        }

        success
    }

    private static boolean recordImage(List<LocalImage> imageStore, Map json) {
        boolean success = false
        if (json.action == "add") {
            LocalImage image = new LocalImage()
            image.creator = json.multimedia[0].creator
            image.created = json.multimedia[0].created
            image.description = json.multimedia[0].description
            image.imageId = json.imageId
            image.licence = json.multimedia[0].licence
            image.originalFileName = json.multimedia[0].originalFilename
            image.rights = json.multimedia[0].rights
            image.rightsHolder = json.multimedia[0].rightsHolder
            image.title = json.multimedia[0].title
            image.contentType = json.multimedia[0].contentType

            imageStore << image
            success = true
        } else if (json.action == "delete") {
            LocalImage image = imageStore.find { it.imageId == json.imageId }
            success = imageStore.remove(image)
        }

        success
    }

    boolean saveBibliography(Profile profile, Map json, boolean deferSave = false) {
        checkArgument profile
        checkArgument json

        if (json.containsKey('bibliography')) {
            profileOrDraft(profile).bibliography = []
            if (json.bibliography) {
                profileOrDraft(profile).bibliography = json.bibliography.collect {
                    new Bibliography(
                            uuid: it.uuid ?: UUID.randomUUID().toString(),
                            text: it.text,
                            order: it.order)
                }
            }

            if (!deferSave) {
                save profile
            }
        }
    }

    boolean updateDocument(Profile profile, Map newDocument, String id) {
        checkArgument profile
        checkArgument newDocument

        profile = profileOrDraft(profile)

        if(profile.documents) {
            profile.documents = new ArrayList<Document>()
        }

        if(id) {
            Document existingDocument = profile.documents.find {
                it.documentId == id
            }

            updateProperties(existingDocument, newDocument)
        }

    }

    /**
     * Updates all properties other than 'id' and converts date strings to BSON dates.
     *
     * Note that dates are assumed to be ISO8601 in UTC with no millisecs
     *
     * Booleans must be handled explicitly because the JSON string "false" will by truthy if just
     *  assigned to a boolean property.
     *
     * @param o the domain instance
     * @param props the properties to use
     */
    private updateProperties(o, props) {
        assert grailsApplication
        def domainDescriptor = grailsApplication.getArtefact(DomainClassArtefactHandler.TYPE,
                o.getClass().name)
        props.remove('id')
        props.remove('api_key')  // don't ever let this be stored in public data
        props.remove('lastUpdated') // in case we are loading from dumped data
        props.each { k, v ->
            log.debug "updating ${k} to ${v}"
            /*
             * Checks the domain for properties of type Date and converts them.
             * Expects dates as strings in the form 'yyyy-MM-ddThh:mm:ssZ'. As indicated by the 'Z' these must be
             * UTC time. They are converted to java dates by forcing a zero time offset so that local timezone is
             * not used. All conversions to and from local time are the responsibility of the service consumer.
             */
            if (v instanceof String && domainDescriptor.hasProperty(k) && domainDescriptor?.getPropertyByName(k)?.getType() == Date) {
                v = v ? parse(v) : null
            }
            if (v == "false") {
                v = false
            }
            if (v == "null") {
                v = null
            }
            o[k] = v
        }
        // always flush the updateDocument so that that any exceptions are caught before the service returns
        o.save(flush: true, failOnError: true)
        if (o.hasErrors()) {
            log.error("has errors:")
            o.errors.each { log.error it }
            throw new Exception(o.errors[0] as String);
        }
    }


    boolean saveBHLLinks(String profileId, Map json, boolean deferSave = false) {
        checkArgument profileId
        checkArgument json

        Profile profile = Profile.findByUuid(profileId)
        checkState profile

        if (json.containsKey("links")) {
            profileOrDraft(profile).bhlLinks = []
            if (json.links) {
                profileOrDraft(profile).bhlLinks = json.links.collect {
                    Link link = new Link(uuid: it.uuid ?: UUID.randomUUID().toString())
                    link.url = it.url
                    link.title = it.title
                    link.description = it.description
                    link.fullTitle = it.fullTitle
                    link.edition = it.edition
                    link.publisherName = it.publisherName
                    link.doi = it.doi
                    link
                }
            }

            if (!deferSave) {
                save profile
            }
        }
    }

    boolean saveLinks(String profileId, Map json, boolean deferSave = false) {
        checkArgument profileId
        checkArgument json

        Profile profile = Profile.findByUuid(profileId)
        checkState profile

        if (json.containsKey("links")) {
            profileOrDraft(profile).links = []
            if (json.links) {
                profileOrDraft(profile).links = json.links.collect {
                    Link link = new Link(uuid: it.uuid ?: UUID.randomUUID().toString())
                    link.url = it.url
                    link.title = it.title
                    link.description = it.description
                    link
                }
            }

            if (!deferSave) {
                save profile
            }
        }
    }

    /**
     *  Related Business Capability:  Allow users to keep historical versions of their profiles
     *  Related Application Feature: Save Snapshot Version feature of Profile Hub
     *
     *  A publication is either a PDF of the profile as a versioned snapshot in time OR
     *  if the profile has additional attachments, a ZIP file containing the profile PDF and
     *  any other file attachments.
     *
     * @param profileId
     * @param file - a pdf of the Profile that has already been generated and sent to this service
     * @return
     */
    def savePublication(String profileId, MultipartFile file) {
        checkArgument profileId
        checkArgument file

        Profile profile = Profile.findByUuid(profileId)

        checkState profile

        if (!profile.publications) {
            profile.publications = []
        }

        Publication publication = new Publication()
        publication.title = profile.scientificName
        publication.authors = profile.authorship.find { it.category.name ==~ /\b(?i)author.*/ }?.text
        publication.publicationDate = new Date()
        publication.userId = authService.getUserId()
        publication.uuid = UUID.randomUUID().toString()
        if (profile.publications) {
            publication.version = profile.publications.sort { it.version }.last().version ?: profile.publications.size()
            publication.version += 1
        } else {
            publication.version = 1
        }

        Map doiResult = doiService.mintDOI(profile.opus, publication, profile)
        if (doiResult.status == "success") {
            publication.doi = doiResult.doi

            if (profile.attachments) {
                String fileName = "${grailsApplication.config.snapshot.directory}/${publication.uuid}.zip"
                savePublicationWithAttachments(profile, file, fileName)
                publication.setFileType(StorageExtension.ZIP)
            } else {
                String fileName = "${grailsApplication.config.snapshot.directory}/${publication.uuid}.pdf"
                //this copies the incoming 'file' data into a file object and saves this object to the file system
                file.transferTo(new File(fileName))
                publication.setFileType(StorageExtension.PDF)
            }
            profile.publications << publication
            save profile
            publication
        } else {
            doiResult
        }
    }

    File getPublicationFile(String publicationId) {
        checkArgument publicationId
        String extension = determineFileExtension(publicationId)
        String fileName = "${grailsApplication.config.snapshot.directory}/${publicationId}${extension}"

        new File(fileName)
    }

    /**
     * Makes and saves a publication for a profile that has attachments when user clicks
     * createDocument snapshot version on Profile-Hub.
     * Takes the profile data, as sent via Profile-Hub as a multipartFile and all attachments for
     * this profile, bundles them up into a zip file and saves them to disk
     * @param profile
     * @param multipartFile - what will become a pdf of the profile
     * @param absoluteFileName
     */
    void savePublicationWithAttachments(Profile profile, MultipartFile multipartFile, String absoluteFileName) {
        ZipOutputStream outputStream = new ZipOutputStream(new FileOutputStream(absoluteFileName))
        Map<String,File> fileMap = [:]
        String profileName = fixFileName(StringUtils.removeEnd(profile.getScientificName(), '.'))
        fileMap.putAll(attachmentService.collectAllAttachmentsIncludingOriginalNames(profile))
        makeAndSaveZipFile(multipartFile, profileName, fileMap, outputStream)
    }

    String fixFileName(String profileName) {
        Calendar cal = Calendar.getInstance()
        profileName + ' - ' + cal.getTime() + StorageExtension.PDF.extension
    }

    private static void makeAndSaveZipFile(MultipartFile multipartFile, String profileName, Map<String,File> fileMap, ZipOutputStream outputStream) {
        try {
            fileMap.each { file ->
                outputStream.putNextEntry(new ZipEntry(file.key))
                file.value.withInputStream
                        { inputStream ->
                            outputStream << inputStream
                        }
                outputStream.closeEntry()
            }
            outputStream.putNextEntry(new ZipEntry(profileName))
            outputStream << multipartFile.getInputStream()
            outputStream.closeEntry()
        } finally {
            outputStream?.flush()
            outputStream?.close()
        }
    }

    //Publications can be either a pdf or zip, only the publication knows what it is, publications
    //are only accessible via their parent profile
    private String determineFileExtension(String publicationid) {
        Profile profile = getProfileFromPubId(publicationid)
        List<Publication> publicationList = profile?.getPublications()
        Publication publication = publicationList?.find { item -> item.uuid.equals(publicationid) }
        String fileExtension = publication.getFileType().extension
        return fileExtension
    }

    Set<Publication> listPublications(String profileId) {
        checkArgument profileId

        Profile profile = Profile.findByUuid(profileId)

        profile?.publications
    }

    Attribute createAttribute(String profileId, Map data) {
        checkArgument profileId
        checkArgument data

        log.debug("Creating new attribute for profile ${profileId} with data ${data}")
        Profile profile = Profile.findByUuid(profileId)
        checkState profile

        List<Contributor> creators = []
        data.creators.each {
            creators << getOrCreateContributor(it)
        }

        List<Contributor> editors = []
        data.editors.each {
            editors << getOrCreateContributor(it)
        }

        // only add the current user as the creator if the attribute was not copied from another profile
        if (!data.original) {
            def contributor
            if (data.attributeTo) {
                contributor = getOrCreateContributor(data.attributeTo)
            } else {
                contributor = getOrCreateContributor(data.userDisplayName, data.userId)
            }
            creators << contributor
        }

        // if we're copying the attribute from another collection and the significant edit flag is set, then add the user as an editor
        if (data.original && data.significantEdit) {
            def contributor
            if (data.attributeTo) {
                contributor = getOrCreateContributor(data.attributeTo)
            } else {
                contributor = getOrCreateContributor(data.userDisplayName, data.userId)
            }
            editors << contributor
        }

        Term titleTerm = vocabService.getOrCreateTerm(data.title, profile.opus.attributeVocabUuid)

        Attribute attribute = new Attribute(
                uuid: UUID.randomUUID().toString(),
                title: titleTerm,
                text: data.text,
                source: data.source
        )
        attribute.creators = creators
        attribute.editors = editors

        if (data.original) {
            Attribute original = Attribute.findByUuid(data.original.uuid)
            attribute.original = original
        }

        if (profile.draft) {
            if (!profile.draft.attributes) {
                profile.draft.attributes = []
            }
            profile.draft.attributes << attribute
        } else {
            attribute.profile = profile
            profile.addToAttributes(attribute)
        }

        profileOrDraft(profile).lastAttributeChange = "Added ${attribute.title.name}"

        boolean success = save profile
        if (!success) {
            attribute = null
        }

        attribute
    }

    Contributor getOrCreateContributor(String name, String userId = null) {
        Contributor contributor = userId ? Contributor.findByUserId(userId) : Contributor.findByName(name)
        if (!contributor) {
            contributor = new Contributor(userId: userId, name: name)
            contributor.save(flush: true)
        }
        contributor
    }

    boolean updateAttribute(String attributeId, String profileId, Map data) {
        checkArgument attributeId
        checkArgument profileId
        checkArgument data

        log.debug("Updating attribute ${attributeId}")

        Profile profile = Profile.findByUuid(profileId)
        checkState profile

        Attribute attribute
        if (profile.draft) {
            attribute = profile.draft.attributes.find { it.uuid == attributeId }
        } else {
            attribute = profile.attributes.find { it.uuid == attributeId }
        }
        checkState attribute

        if (data.title) {
            Term titleTerm = vocabService.getOrCreateTerm(data.title, profile.opus.attributeVocabUuid)
            attribute.title = titleTerm
        }
        attribute.text = data.text
        attribute.source = data.source

        def contributor
        if (data.attributeTo) {
            contributor = getOrCreateContributor(data.attributeTo)
        } else {
            contributor = getOrCreateContributor(data.userDisplayName, data.userId)
        }

        if (!attribute.editors) {
            attribute.editors = []
        }

        if (!attribute.editors.contains(contributor) && data.significantEdit) {
            attribute.editors << contributor
        }

        profileOrDraft(profile).lastAttributeChange = "Updated ${attribute.title.name} - ${Utils.cleanupText(attribute.text)}"

        save profile
    }

    boolean deleteAttribute(String attributeId, String profileId) {
        checkArgument attributeId
        checkArgument profileId

        Profile profile = Profile.findByUuid(profileId)
        checkState profile

        Attribute attr
        if (profile.draft) {
            attr = profile.draft.attributes.find { it.uuid == attributeId }
            profile.draft.attributes.remove(attr)
            profile.draft.lastAttributeChange = "Deleted ${attr.title.name} - ${Utils.cleanupText(attr.text)}"

            save profile
        } else {
            attr = profile.attributes.find { it.uuid == attributeId }
            checkState attr

            profile.attributes.remove(attr)

            profile.lastAttributeChange = "Deleted ${attr.title.name} - ${Utils.cleanupText(attr.text)}"

            save profile

            delete attr
        }
    }

    Profile getProfileFromPubId(String pubId) {
        List<Profile> profiles = Profile.withCriteria {
            eq("publications.uuid", pubId)
        };
        profiles.size() > 0 ? profiles.get(0) : null;
    }

    List<Attachment> saveAttachment(String profileId, Map metadata, CommonsMultipartFile file) {
        Profile profile = Profile.findByUuid(profileId)
        checkState profile

        Date createdDate = metadata.createdDate ? new SimpleDateFormat("yyyy-MM-dd").parse(metadata.createdDate) : null

        if (metadata.uuid) {
            Attachment existing = profileOrDraft(profile).attachments.find { it.uuid == metadata.uuid }
            if (existing) {
                existing.url = metadata.url
                existing.title = metadata.title
                existing.description = metadata.description
                existing.rights = metadata.rights
                existing.rightsHolder = metadata.rightsHolder
                existing.licence = metadata.licence
                existing.creator = metadata.creator
                existing.createdDate = createdDate
            }
        } else {
            Attachment newAttachment = new Attachment(uuid: UUID.randomUUID().toString(), url: metadata.url,
                    title: metadata.title, description: metadata.description, filename: metadata.filename,
                    contentType: file?.contentType, rights: metadata.rights, createdDate: createdDate,
                    rightsHolder: metadata.rightsHolder, licence: metadata.licence, creator: metadata.creator)
            if (file) {
                String extension = Utils.getFileExtension(file.originalFilename)
                attachmentService.saveAttachment(profile.opus.uuid, profile.uuid, newAttachment.uuid, file, extension)
            }
            profileOrDraft(profile).attachments << newAttachment
        }

        save profile

        profile.attachments
    }

    List<Attachment> deleteAttachment(String profileId, String attachmentId) {
        Profile profile = Profile.findByUuid(profileId)
        checkState profile

        Attachment attachment = profileOrDraft(profile).attachments?.find { it.uuid == attachmentId }
        if (attachment) {
            profileOrDraft(profile).attachments.remove(attachment)

            // Only delete the file if we are not in draft mode.
            // If we are in draft mode, then the file will be deleted when the draft is published.
            if (!profile.draft && attachment.filename) {
                attachmentService.deleteAttachment(profile.opus.uuid, profile.uuid, attachment.uuid, Utils.getFileExtension(attachment.filename))
            }
        }

        save profile

        profile.attachments
    }

    /**
     * Converts the domain object into a map of properties, including
     * dynamic properties.
     * @param document an Document instance
     * @param levelOfDetail list of features to include
     * @return map of properties
     */
    def documentToMap(document, levelOfDetail = []) {
        def mapOfProperties = document instanceof Document ? document.getProperty("dbo").toMap() : document
        def id = mapOfProperties["_id"].toString()
        mapOfProperties["id"] = id
        mapOfProperties.remove("_id")
        // construct document url based on the current configuration
        mapOfProperties.url = document.url
        mapOfProperties.findAll { k, v -> v != null }
    }

    /**
     * Creates a new Document object associated with the supplied file.
     * @param props the desired properties of the Document.
     */
    def createDocument(Profile originalProfile, props) {

        checkArgument originalProfile

        def profile = profileOrDraft(originalProfile)

        if(!profile.documents) {
            profile.documents = new ArrayList<Document>()
        }

        def d = new Document(documentId: UUID.randomUUID().toString())

        try {
            profile.documents << d
            save originalProfile
            props.remove 'documentId'

            updateDocumentProperties(d, props)
            save originalProfile
            return [status: 'ok', documentId: d.documentId, url: d.url]
        } catch (Exception e) {
            // clear session to avoid exception when GORM tries to autoflush the changes
            e.printStackTrace()

            Document.withSession { session -> session.clear() }
            def error = "Error creating document for ${props.filename} - ${e.message}"
            log.error error
            return [status: 'error', error: error]
        }
    }

    def deleteDocument(String profileId, String documentId, boolean destroy = false) {

        checkArgument profileId
        checkArgument documentId

        Profile originalProfile = Profile.findByUuid(profileId)
        checkState originalProfile

        def profile = profileOrDraft(originalProfile)

        if (!profile.documents) {
            profile.documents = new ArrayList<Document>()
        }

        Document document = profile.documents.find {
            it.documentId == documentId
        }
        if (document) {
            try {
                profile.documents.remove(document)
                save originalProfile
                return [status: 'ok', documentId: document.documentId]
            } catch (Exception e) {
                Profile.withSession { session -> session.clear() }
                def error = "Error deleting document ${documentId} - ${e.message}"
                log.error error, e
                return [status: 'error', error: error]
            }
        } else {
            def error = "Error deleting document - no such id ${documentId}"
            log.error error
            return [status: 'error', error: error]
        }
    }


    /**
     * Updates all properties other than 'id' and converts date strings to BSON dates.
     *
     * Note that dates are assumed to be ISO8601 in UTC with no millisecs
     *
     * Booleans must be handled explicitly because the JSON string "false" will by truthy if just
     *  assigned to a boolean property.
     *
     * @param o the domain instance
     * @param props the properties to use
     */
    private updateDocumentProperties(o, props) {
        assert grailsApplication
        def domainDescriptor = grailsApplication.getArtefact(DomainClassArtefactHandler.TYPE,
                o.getClass().name)
        props.remove('id')
        props.remove('api_key')  // don't ever let this be stored in public data
        props.remove('lastUpdated') // in case we are loading from dumped data
        props.each { k, v ->
            log.debug "updating ${k} to ${v}"
            /*
             * Checks the domain for properties of type Date and converts them.
             * Expects dates as strings in the form 'yyyy-MM-ddThh:mm:ssZ'. As indicated by the 'Z' these must be
             * UTC time. They are converted to java dates by forcing a zero time offset so that local timezone is
             * not used. All conversions to and from local time are the responsibility of the service consumer.
             */
            if (v instanceof String && domainDescriptor.hasProperty(k) && domainDescriptor?.getPropertyByName(k)?.getType() == Date) {
                v = v ? parseDate(v) : null
            }
            if (v == "false") {
                v = false
            }
            if (v == "null") {
                v = null
            }
            o[k] = v
        }
    }

    static dateFormat = new SimpleDateFormat("yyyy-MM-dd'T'hh:mm:ssZ")

    private Date parseDate(String dateStr) {
        return dateFormat.parse(dateStr.replace("Z", "+0000"))
    }

    def listDocument(Profile originalProfile, boolean editMode = false) {

        checkArgument originalProfile

        def profile = editMode ? profileOrDraft(originalProfile) : originalProfile

        List<Document> documents = profile.documents ?: new ArrayList<Document>()

        [documents: documents.collect { documentToMap(it) }, count: documents.size()]
    }

    def setPrimaryMultimedia(Profile originalProfile, json) {

        checkArgument originalProfile

        def profile = profileOrDraft(originalProfile)

        profile.primaryAudio = json?.primaryAudio ?: null
        profile.primaryVideo = json?.primaryVideo ?: null

        originalProfile.save(true)

        return !originalProfile.hasErrors()
    }

    def setStatus(Profile originalProfile, json) {
        checkArgument originalProfile
        checkArgument json?.status
        def profile = profileOrDraft(originalProfile)

        profile.profileStatus = json?.status

        save originalProfile

        return !originalProfile.hasErrors()
    }

    public String getProfileIdentifierForMapQuery(Object profile) {
        String query = ""
        if (profile.guid && profile.guid != "null") {
            query += "${"lsid:${profile.guid}"}"
        } else {
            query += profile.scientificName
        }
        query
    }
}

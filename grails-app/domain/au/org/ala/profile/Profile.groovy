package au.org.ala.profile

import au.org.ala.profile.util.ImageOption
import groovy.transform.EqualsAndHashCode
import groovy.transform.ToString
import org.bson.types.ObjectId

import javax.persistence.Transient

@EqualsAndHashCode(includes = ['guid', 'scientificName', 'nameAuthor', 'fullName', 'rank', 'opus'])
@ToString(includes = ['guid', 'scientificName', 'nameAuthor', 'fullName', 'rank', 'opus'])
class Profile {

    private static final String NOT_ANALYZED_INDEX = "not_analyzed"

    final static STATUS_EMPTY = 'Empty'
    final static STATUS_PARTIAL = 'Partial'
    final static STATUS_LEGACY = 'Legacy'
//    static STATUS_COMPLETE = 'complete'
//    static STATUS_IN_REVIEW = 'in_review'

    static searchable = {
        only = ["uuid", "guid", "scientificName", "fullName", "matchedName", "rank", "primaryImage", "opus",
                "attributes", "lastUpdated", "archivedDate", "archivedWithName", "scientificNameLower",
                "archivedNameLower", "matchedNameLower", "fullNameLower", "nameAuthor", 'profileStatus']
        scientificName multi_field: true // TODO add to queries, boost: 20
        archivedWithName multi_field: true // TODO add to queries, boost: 20
        matchedName component: true // TODO add to queries, boost: 10
        opus component: true
        attributes component: true
        nslNameIdentifier index: NOT_ANALYZED_INDEX
        uuid index: NOT_ANALYZED_INDEX
        guid index: NOT_ANALYZED_INDEX
        lastUpdated index: NOT_ANALYZED_INDEX
        rank index: NOT_ANALYZED_INDEX
        primaryImage index: NOT_ANALYZED_INDEX
        scientificNameLower index: NOT_ANALYZED_INDEX
        archivedNameLower index: NOT_ANALYZED_INDEX
        matchedNameLower index: NOT_ANALYZED_INDEX
        fullNameLower index: NOT_ANALYZED_INDEX
        nameAuthor index: NOT_ANALYZED_INDEX
        profileStatus index: NOT_ANALYZED_INDEX
    }

    ObjectId id
    String uuid
    String guid                 //taxon GUID / LSID
    String scientificName
    String nameAuthor
    String fullName
    String rank
    String nslNameIdentifier
    String nslNomenclatureIdentifier
    String nslProtologue
    String occurrenceQuery
    boolean isCustomMapConfig = false

    String profileStatus = STATUS_PARTIAL
    Integer emptyProfileVersion

    @Transient
    boolean privateMode = false

    Name matchedName
    boolean manuallyMatchedName = false
    String taxonomyTree
    String primaryImage
    Map<String, ImageSettings> imageSettings = [:]
    String primaryVideo
    String primaryAudio
    boolean showLinkedOpusAttributes = false // Even if set to true, this needs Opus.showLinkedOpusAttributes to also be true
    List<String> specimenIds
    List<Authorship> authorship
    List<Classification> classification
    boolean manualClassification = false
    List<Link> links
    List<Link> bhlLinks
    List<Bibliography> bibliography
    List<Document> documents
    List<Publication> publications

    List<LocalImage> privateImages = []
    List<LocalImage> stagedImages = [] // this is only used when dealing with draft profiles

    List<Attachment> attachments

    String lastAttributeChange

    Date dateCreated
    String createdBy
    Date lastUpdated
    String lastUpdatedBy

    Date lastPublished // The last time the profile was saved that wasn't a change to a draft.

    DraftProfile draft

    String archiveComment
    Date archivedDate
    String archivedBy
    String archivedWithName

    String scientificNameLower

    @Transient
    String getFullNameLower() { fullName?.toLowerCase() }
    @Transient
    String getArchivedNameLower() { archivedWithName?.toLowerCase() }
    @Transient
    String getMatchedNameLower() { matchedName?.scientificName?.toLowerCase() }

    static embedded = ['authorship', 'classification', 'draft', 'links', 'bhlLinks', 'publications', 'bibliography', 'documents', 'matchedName', 'privateImages', 'attachments', 'imageSettings']

    static hasMany = [attributes: Attribute]

    static belongsTo = [opus: Opus]

    static constraints = {
        nameAuthor nullable: true
        fullName nullable: true
        guid nullable: true
        primaryImage nullable: true, validator: { val, obj ->
            !val || (obj?.imageSettings[val]?.imageDisplayOption ?: ImageOption.INCLUDE) == ImageOption.INCLUDE
        }
        primaryVideo nullable: true
        primaryAudio nullable: true
        specimenIds nullable: true
        classification nullable: true
        nslNameIdentifier nullable: true
        nslNomenclatureIdentifier nullable: true
        nslProtologue nullable: true
        rank nullable: true
        draft nullable: true
        taxonomyTree nullable: true
        matchedName nullable: true
        createdBy nullable: true
        lastUpdatedBy nullable: true
        lastAttributeChange nullable: true
        archiveComment nullable: true
        archivedDate nullable: true
        archivedBy nullable: true
        archivedWithName nullable: true
        occurrenceQuery nullable: true
        profileStatus nullable: true
        emptyProfileVersion nullable: true
        lastPublished nullable: true
    }

    static mapping = {
        attributes cascade: "all-delete-orphan"
        scientificName index: true
        scientificNameLower index: true
        guid index: true
        rank index: true
        uuid index: true
        opus index: true
        nameAuthor index: true
        profileStatus defaultValue: STATUS_PARTIAL
    }

    private def updateScientificNameLower() {
        if (scientificName) {
            def lower = scientificName.toLowerCase()
            if (lower != scientificNameLower) {
                scientificNameLower = lower
            }
        }
        if (draft?.scientificName) {
            def lower = draft.scientificName.toLowerCase()
            if (lower != draft.scientificNameLower) {
                draft.scientificNameLower = lower
            }
        }
    }

    def beforeUpdate() {
        updateScientificNameLower()
    }

    def beforeValidate() {
        if (uuid == null) {
            uuid = UUID.randomUUID().toString()
        }

        updateScientificNameLower()

        // draft nullness is not enough to know if the profile or the draft has been edited
        // we need to play with the dirty properties to check for additional conditions
        // such as when the draft is created, cancelled or published.

        if(isDirty("draft") && dirtyPropertyNames.size() == 1 ) {
            if (draft) { // Draft Created
                draft.lastPublished = lastPublished // No changes to the draft have happened yet
            }
            // else => Draft cancelled
            // Do nothing, draft will be wiped out and this.lastPublished remains untouched
        } else if(draft) { // Draft Updated
            draft.lastPublished = new Date()
        } else {
            lastPublished = new Date()
            // For completeness below is the condition for a Draft published but we only need to update this.lastPublished hence no more code required.
            // !draft && isDirty("draft") && dirtyPropertyNames.size() > 1
        }
    }
}

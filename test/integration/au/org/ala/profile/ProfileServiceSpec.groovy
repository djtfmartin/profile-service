package au.org.ala.profile

import au.org.ala.profile.util.ImageOption
import au.org.ala.web.UserDetails
import spock.lang.Unroll

import static au.org.ala.profile.util.ImageOption.*
import au.org.ala.web.AuthService
import org.springframework.web.multipart.MultipartFile
import org.springframework.web.multipart.commons.CommonsMultipartFile

class ProfileServiceSpec extends BaseIntegrationSpec {

    ProfileService service = new ProfileService()
    BieService bieService

    def setup() {
        service.nameService = Mock(NameService)
        service.nameService.matchName(_) >> [scientificName: "sciName", author: "fred", guid: "ABC"]
        service.authService = Mock(AuthService)
        service.authService.getUserId() >> "fred"
        service.authService.getUserForUserId(_) >> new UserDetails(userId: "1234", firstName: "fred", lastName: "fred")
        bieService = Mock(BieService)
        bieService.getClassification(_) >> null
        service.bieService = bieService
        service.doiService = Mock(DoiService)
        service.doiService.mintDOI(_, _, _) >> [status: "success", doi: "1234"]
        service.grailsApplication = [config: [snapshot: [directory: "bla"]]]
        service.vocabService = Mock(VocabService)
        service.vocabService.getOrCreateTerm(_, _) >> { name, id -> [name: name, vocabId: id] }
        service.attachmentService = Mock(AttachmentService)
    }

    def "createProfile should expect both arguments to be provided"() {
        when:
        service.createProfile(null, [a: "b"])

        then:
        thrown(IllegalArgumentException)

        when:
        service.createProfile("bla", [:])

        then:
        thrown(IllegalArgumentException)
    }

    def "createProfile should fail if the specified opus does not exist"() {
        when:
        service.createProfile("unknown", [a: "bla"])

        then:
        thrown(IllegalStateException)
    }

    def "createProfile should return the new profile on success"() {
        given:
        Opus opus = new Opus(glossary: new Glossary(), dataResourceUid: "dr1234", title: "title")
        save opus

        when:
        Profile profile = service.createProfile(opus.uuid, [scientificName: "sciName"])

        then:
        profile != null && profile.id != null
        Profile.count() == 1
        Profile.list()[0].scientificName == "sciName"
    }

    def "createProfile should default the 'author' authorship to the current user"() {
        given:
        Opus opus = new Opus(glossary: new Glossary(), dataResourceUid: "dr1234", title: "title")
        save opus

        when:
        Profile profile = service.createProfile(opus.uuid, [scientificName: "sciName"])

        then:
        profile.authorship.size() == 1
        profile.authorship[0].category.name == "Author"
        profile.authorship[0].text == "fred fred"
    }

    def "createProfile should fail if the primary image is excluded"() {
        Opus opus = new Opus(glossary: new Glossary(), dataResourceUid: "dr1234", title: "title")
        save opus

        when:
        String image = UUID.randomUUID().toString()
        Profile profile = service.createProfile(opus.uuid, [scientificName: "sciName", primaryImage: image, imageSettings: [(image): new ImageSettings(imageDisplayOption: ImageOption.EXCLUDE, caption: '')]])

        then:
        profile == null
    }

    def "createProfile should NOT automatically put the profile in draft mode if the Opus.autoDraftProfiles flag = false"() {
        given:
        Opus opus = new Opus(glossary: new Glossary(), dataResourceUid: "dr1234", title: "title", autoDraftProfiles: false)
        save opus

        when:
        Profile profile = service.createProfile(opus.uuid, [scientificName: "sciName"])

        then:
        profile.draft == null
    }

    def "createProfile should automatically put the profile in draft mode if the Opus.autoDraftProfiles flag = true"() {
        given:
        Opus opus = new Opus(glossary: new Glossary(), dataResourceUid: "dr1234", title: "title", autoDraftProfiles: true)
        save opus

        when:
        Profile profile = service.createProfile(opus.uuid, [scientificName: "sciName"])

        then:
        profile.draft != null
    }

    def "duplicateProfile should fail if the profile to copy is null"() {
        when:
        service.duplicateProfile("123", null, [scientificName: "test"])

        then:
        thrown(IllegalArgumentException)
    }

    def "duplicateProfile should create a new profile with the content of the profile to copy"() {
        given:
        Opus opus = new Opus(glossary: new Glossary(), dataResourceUid: "dr1234", title: "title")
        save opus
        Vocab vocab = new Vocab(name: "vocab1")
        Term term = new Term(uuid: "1", name: "title")
        vocab.addToTerms(term)
        save vocab
        Profile existingProfile = new Profile(opus: opus,
                scientificName: "name",
                specimenIds: ["1", "2"],
                links: [new Link(uuid: "L1", title: "link1")],
                bhlLinks: [new Link(uuid: "B1", title: "bhl1")],
                bibliography: [new Bibliography(uuid: "B1", text: "bib1")],
                authorship: [new Authorship(term: term, text: "bib1")]
        )
        save existingProfile

        expect:
        Profile.count() == 1

        when:
        Profile newProfile = service.duplicateProfile(opus.uuid, existingProfile, [scientificName: "test"])

        then:
        Profile.count() == 2
        newProfile.uuid != null
        newProfile.uuid != existingProfile.uuid
        newProfile.specimenIds == ["1", "2"]
        !newProfile.specimenIds.is(existingProfile.specimenIds)
        newProfile.links.size() == 1
        newProfile.links[0] != existingProfile.links[0]
        newProfile.bhlLinks.size() == 1
        newProfile.bhlLinks[0] != existingProfile.bhlLinks[0]
        newProfile.bibliography.size() == 1
        newProfile.bibliography[0] != existingProfile.bibliography[0]
        newProfile.authorship.size() == 1
        !newProfile.authorship[0].is(existingProfile.authorship[0]) // authorship doens't have an ID, so object equality will match, but reference equality should not
    }

    def "duplicateProfile should clone the attributes for the profile being duplicated"() {
        given:
        Opus opus = new Opus(glossary: new Glossary(), dataResourceUid: "dr1234", title: "title")
        save opus
        Profile existingProfile = new Profile(opus: opus, scientificName: "name", specimenIds: ["1", "2"])

        Vocab vocab = new Vocab(name: "vocab1")
        Term term = new Term(uuid: "1", name: "title")
        vocab.addToTerms(term)
        save vocab
        Attribute attribute1 = new Attribute(uuid: "1", title: term, text: "text")
        existingProfile.addToAttributes(attribute1)
        Attribute attribute2 = new Attribute(uuid: "2", title: term, text: "text2")
        existingProfile.addToAttributes(attribute2)

        save existingProfile

        expect:
        Profile.count() == 1
        Attribute.count() == 2

        when:
        Profile newProfile = service.duplicateProfile(opus.uuid, existingProfile, [scientificName: "test"])

        then:
        Profile.count() == 2
        Attribute.count() == 4
        Profile.list()[0].is(existingProfile)
        !Profile.list()[1].is(existingProfile)
        newProfile.attributes.size() == 2
        !newProfile.attributes.is(existingProfile.attributes)
        newProfile.attributes[0].uuid != "1" && newProfile.attributes[0].uuid != "2"
        newProfile.attributes[1].uuid != "1" && newProfile.attributes[1].uuid != "2"
    }

    def "duplicateProfile with Opus.autoDraftProfiles puts the attributes in before creating a draft"() {
        given:
        Opus opus = new Opus(glossary: new Glossary(), dataResourceUid: "dr1234", title: "title", autoDraftProfiles: true)
        save opus
        Profile existingProfile = new Profile(opus: opus, scientificName: "name", specimenIds: ["1", "2"])

        Vocab vocab = new Vocab(name: "vocab1")
        Term term = new Term(uuid: "1", name: "title")
        vocab.addToTerms(term)
        save vocab
        Attribute attribute1 = new Attribute(uuid: "1", title: term, text: "text")
        existingProfile.addToAttributes(attribute1)
        Attribute attribute2 = new Attribute(uuid: "2", title: term, text: "text2")
        existingProfile.addToAttributes(attribute2)

        save existingProfile

        expect:
        Profile.count() == 1
        Attribute.count() == 2

        when:
        Profile newProfile = service.duplicateProfile(opus.uuid, existingProfile, [scientificName: "test"])

        then:
        newProfile.attributes.size() == 2
        newProfile.draft.attributes.size() == 2
        !newProfile.attributes*.uuid.containsAll(["1", "2"])
        !newProfile.draft.attributes*.uuid.containsAll(["1", "2"])
    }

    def "delete profile should remove the record"() {
        given:
        Opus opus = new Opus(glossary: new Glossary(), dataResourceUid: "dr1234", title: "title")
        save opus
        Profile profile = new Profile(opus: opus, scientificName: "name")
        save profile
        String profileId = profile.uuid

        expect:
        Profile.count() == 1

        when:
        service.deleteProfile(profileId)

        then:
        Profile.count() == 0
    }

    def "delete should throw IllegalArgumentException if no profile id is provided"() {
        when:
        service.deleteProfile(null)

        then:
        thrown(IllegalArgumentException)
    }

    def "updateProfile should throw IllegalArgumentException if no profile id or data are provided"() {
        when:
        service.updateProfile("something", null)

        then:
        thrown(IllegalArgumentException)

        when:
        service.updateProfile(null, [a: "bla"])

        then:
        thrown(IllegalArgumentException)
    }

    def "updateProfile should throw IllegalStateException if the profile does not exist"() {
        when:
        service.updateProfile("bla", [a: "bla"])

        then:
        thrown(IllegalStateException)
    }

    def "updateProfile should update all data provided"() {
        given:
        Opus opus = new Opus(glossary: new Glossary(), dataResourceUid: "dr1234", title: "title")
        save opus
        Profile profile = new Profile(opus: opus, scientificName: "sciName", primaryImage: "12345")
        save profile

        when: "incoming data contains a variety of fields"
        Map data = [primaryImage : "09876",
                    imageSettings: [[imageId: "image4", caption: '', displayOption: EXCLUDE.name()], [imageId: "image5", caption: 'potato', displayOption: EXCLUDE.name()], [imageId: "image6", displayOption: EXCLUDE.name()]],
                    specimenIds  : ["4", "5", "6"],
                    bhlLinks     : [[url: "three"], [url: "four"]],
                    links        : [[url: "five"], [url: "six"]],
                    bibliography : [[text: "bib1"], [text: "bib2"]]]
        service.updateProfile(profile.uuid, data)

        then: "all appropriate fields are updated"
        profile.primaryImage == "09876"
        profile.imageSettings == [image4: new ImageSettings(imageDisplayOption: EXCLUDE), image5: new ImageSettings(imageDisplayOption: EXCLUDE, caption: 'potato'), image6: new ImageSettings(imageDisplayOption: EXCLUDE)]
        profile.specimenIds == ["4", "5", "6"]
        profile.bhlLinks.every { it.url == "three" || it.url == "four" }
        profile.links.every { it.url == "five" || it.url == "six" }
        profile.bibliography.each { it.text == "bib1" || it.text == "bib2" }
    }

    def "saveImages should change the primary image only if the new data contains the element"() {
        given:
        Opus opus = new Opus(glossary: new Glossary(), dataResourceUid: "dr1234", title: "title")
        save opus
        Profile profile = new Profile(opus: opus, scientificName: "sciName", primaryImage: "12345")
        save profile

        when: "the incoming data does not have a primaryImage attribute"
        service.saveImages(profile, [a: "bla"])

        then: "there should be no change"
        profile.primaryImage == "12345"
    }

    def "saveImages should change the primary image only if the new data contains the element and the value is different"() {
        given:
        Opus opus = new Opus(glossary: new Glossary(), dataResourceUid: "dr1234", title: "title")
        save opus
        Profile profile

        when: "the incoming primary image is null"
        profile = new Profile(opus: opus, scientificName: "sciName", primaryImage: "12345")
        save profile
        service.saveImages(profile, [primaryImage: null])

        then: "the primary image should be cleared"
        profile.primaryImage == null

        when: "the incoming primary image is different"
        profile = new Profile(opus: opus, scientificName: "sciName", primaryImage: "12345")
        save profile
        service.saveImages(profile, [primaryImage: "09876"])

        then: "the profile should be updated"
        profile.primaryImage == "09876"

        when: "the incoming data does not have a primaryImage attribute"
        profile = new Profile(opus: opus, scientificName: "sciName", primaryImage: "12345")
        save profile
        service.saveImages(profile, [a: "bla"])

        then: "there should be no change"
        profile.primaryImage == "12345"
    }

    def "saveImages should change the imageSettings only if the incoming data has the imageSettings attribute and the value is different"() {
        given:
        Opus opus = new Opus(glossary: new Glossary(), dataResourceUid: "dr1234", title: "title")
        save opus
        Profile profile

        when: "the incoming value is different"
        profile = new Profile(opus: opus, scientificName: "sciName", imageSettings: [image1: new ImageSettings(imageDisplayOption: EXCLUDE), image2: new ImageSettings(imageDisplayOption: EXCLUDE), image3: new ImageSettings(imageDisplayOption: EXCLUDE)])
        save profile
        service.saveImages(profile, [imageSettings: [[imageId: "image4", displayOption: EXCLUDE.name()], [imageId: "image5", displayOption: EXCLUDE.name()], [imageId: "image6", displayOption: EXCLUDE.name()]]])

        then: "the imageSettings should be replaced"
        profile.imageSettings == [image4: new ImageSettings(imageDisplayOption: EXCLUDE), image5: new ImageSettings(imageDisplayOption: EXCLUDE), image6: new ImageSettings(imageDisplayOption: EXCLUDE)]

        when: "the incoming data does not have the attribute"
        profile = new Profile(opus: opus, scientificName: "sciName", imageSettings: [image1: new ImageSettings(imageDisplayOption: EXCLUDE), image2: new ImageSettings(imageDisplayOption: EXCLUDE), image3: new ImageSettings(imageDisplayOption: EXCLUDE)])
        save profile
        service.saveImages(profile, [a: "bla"])

        then: "there should be no change"
        profile.imageSettings == [image1: new ImageSettings(imageDisplayOption: EXCLUDE), image2: new ImageSettings(imageDisplayOption: EXCLUDE), image3: new ImageSettings(imageDisplayOption: EXCLUDE)]

        when: "the incoming attribute is empty"
        profile = new Profile(opus: opus, scientificName: "sciName", imageSettings: [image1: new ImageSettings(imageDisplayOption: EXCLUDE), image2: new ImageSettings(imageDisplayOption: EXCLUDE), image3: new ImageSettings(imageDisplayOption: EXCLUDE)])
        save profile
        service.saveImages(profile, [imageSettings: []])

        then: "all existing image options should be removed"
        profile.imageSettings == [:]

        when: "the incoming attribute is null"
        profile = new Profile(opus: opus, scientificName: "sciName", imageSettings: [image1: new ImageSettings(imageDisplayOption: EXCLUDE), image2: new ImageSettings(imageDisplayOption: EXCLUDE), image3: new ImageSettings(imageDisplayOption: EXCLUDE)])
        save profile
        service.saveImages(profile, [imageSettings: null])

        then: "all existing image options should be removed"
        profile.imageSettings == [:]
    }

    // IT added for completeness but keep in mind that the test was passing even if the DraftProfile.imageSettings was not configured as embedded in the domain class
    def "Draft saveImages should change the imageSettings only if the incoming data has the imageSettings attribute and the value is different"() {
        given:
        Opus opus = new Opus(glossary: new Glossary(), dataResourceUid: "dr1234", title: "title")
        save opus
        Profile profile

        when: "the incoming value is different"
        profile = new Profile(opus: opus, scientificName: "sciName",  draft:  new DraftProfile(uuid: "asd", opus: opus, scientificName: "sciName",imageSettings: [image1: new ImageSettings(imageDisplayOption: EXCLUDE), image2: new ImageSettings(imageDisplayOption: EXCLUDE), image3: new ImageSettings(imageDisplayOption: EXCLUDE)]))
        save profile
        service.saveImages(profile, [imageSettings: [[imageId: "image4", displayOption: EXCLUDE.name()], [imageId: "image5", displayOption: EXCLUDE.name()], [imageId: "image6", displayOption: EXCLUDE.name()]]])

        then: "the imageSettings should be replaced"
        profile.draft.imageSettings == [image4: new ImageSettings(imageDisplayOption: EXCLUDE), image5: new ImageSettings(imageDisplayOption: EXCLUDE), image6: new ImageSettings(imageDisplayOption: EXCLUDE)]

        when: "the incoming data does not have the attribute"
        profile = new Profile(opus: opus, scientificName: "sciName", draft:  new DraftProfile(uuid: "asd", opus: opus, scientificName: "sciName", imageSettings: [image1: new ImageSettings(imageDisplayOption: EXCLUDE), image2: new ImageSettings(imageDisplayOption: EXCLUDE), image3: new ImageSettings(imageDisplayOption: EXCLUDE)]))
        save profile
        service.saveImages(profile, [a: "bla"])

        then: "there should be no change"
        profile.draft.imageSettings == [image1: new ImageSettings(imageDisplayOption: EXCLUDE), image2: new ImageSettings(imageDisplayOption: EXCLUDE), image3: new ImageSettings(imageDisplayOption: EXCLUDE)]

        when: "the incoming attribute is empty"
        profile = new Profile(opus: opus, scientificName: "sciName", draft:  new DraftProfile(uuid: "asd", opus: opus, scientificName: "sciName", imageSettings: [image1: new ImageSettings(imageDisplayOption: EXCLUDE), image2: new ImageSettings(imageDisplayOption: EXCLUDE), image3: new ImageSettings(imageDisplayOption: EXCLUDE)]))
        save profile
        service.saveImages(profile, [imageSettings: []])

        then: "all existing image options should be removed"
        profile.draft.imageSettings == [:]

        when: "the incoming attribute is null"
        profile = new Profile(opus: opus, scientificName: "sciName", draft:  new DraftProfile(uuid: "asd", opus: opus, scientificName: "sciName", imageSettings: [image1: new ImageSettings(imageDisplayOption: EXCLUDE), image2: new ImageSettings(imageDisplayOption: EXCLUDE), image3: new ImageSettings(imageDisplayOption: EXCLUDE)]))
        save profile
        service.saveImages(profile, [imageSettings: null])

        then: "all existing image options should be removed"
        profile.draft.imageSettings == [:]
    }

    def "saveImages should fail if the primary image is excluded"() {
        Opus opus = new Opus(glossary: new Glossary(), dataResourceUid: "dr1234", title: "title")
        save opus
        String image = UUID.randomUUID().toString()
        Profile profile = service.createProfile(opus.uuid, [scientificName: "sciName", primaryImage: image, imageSettings: [(image): new ImageSettings(imageDisplayOption: INCLUDE, caption: '')]])

        when:
        def result = service.saveImages(profile, [imageSettings: [[imageId: image, displayOption: EXCLUDE.name(), caption: 'caption']]])

        then:
        result == false
        Profile.withNewSession { s ->
            Profile.findByOpusAndScientificName(opus, 'sciName')?.imageSettings?.get(image)?.imageDisplayOption == INCLUDE
        }
    }


    def "saveSpecimens should change the specimens only if the incoming data has the specimenIds attribute and the value is different"() {
        given:
        Opus opus = new Opus(glossary: new Glossary(), dataResourceUid: "dr1234", title: "title")
        save opus
        Profile profile

        when: "the incoming data does not have the attribute"
        profile = new Profile(opus: opus, scientificName: "sciName", specimenIds: ["1", "2", "3"])
        save profile
        service.saveSpecimens(profile, [a: "bla"])

        then: "there should be no change"
        profile.specimenIds == ["1", "2", "3"]

        when: "the incoming attribute is empty"
        profile = new Profile(opus: opus, scientificName: "sciName", specimenIds: ["1", "2", "3"])
        save profile
        service.saveSpecimens(profile, [specimenIds: []])

        then: "all existing specimens should be removed"
        profile.specimenIds == []

        when: "the incoming value is different"
        profile = new Profile(opus: opus, scientificName: "sciName", specimenIds: ["1", "2", "3"])
        save profile
        service.saveSpecimens(profile, [specimenIds: ["4", "5", "6"]])

        then: "the profile should be replaced"
        profile.specimenIds == ["4", "5", "6"]

        when: "the incoming attribute is null"
        profile = new Profile(opus: opus, scientificName: "sciName", specimenIds: ["1", "2", "3"])
        save profile
        service.saveSpecimens(profile, [specimenIds: null])

        then: "all existing ss should be removed"
        profile.specimenIds == []

    }

    def "saveSpecimens should save changes to the profile draft if one exists"() {
        given:
        Opus opus = new Opus(glossary: new Glossary(), dataResourceUid: "dr1234", title: "title")
        save opus
        Profile profile

        when:
        profile = new Profile(opus: opus, scientificName: "sciName", specimenIds: ["1", "2", "3"], draft: [uuid: "asd", scientificName: "sciName", specimenIds: ["1", "2", "3"]])
        save profile
        service.saveSpecimens(profile, [specimenIds: ["4", "5", "6"]])

        then: "there should be no change"
        profile.specimenIds == ["1", "2", "3"]
        profile.draft.specimenIds == ["4", "5", "6"]
    }

    def "saveBibliography should not change the bibliography if the incoming data does not have the bibliography attribute"() {
        given:
        Opus opus = new Opus(glossary: new Glossary(), dataResourceUid: "dr1234", title: "title")
        save opus
        Bibliography bib1 = new Bibliography(uuid: "1", text: "one")
        Bibliography bib2 = new Bibliography(uuid: "2", text: "two")
        Profile profile = new Profile(opus: opus, scientificName: "sciName", bibliography: [bib1, bib2])
        save profile

        when: "the incoming data does not have the attribute"
        service.saveBibliography(profile, [a: "bla"])

        then: "there should be no change"
        profile.bibliography.contains(bib1) && profile.bibliography.contains(bib2)
    }

    def "saveBibliography should not change the bibliography if the incoming data is the same"() {
        given:
        Opus opus = new Opus(glossary: new Glossary(), dataResourceUid: "dr1234", title: "title")
        save opus
        Bibliography bib1 = new Bibliography(uuid: "1", text: "one")
        Bibliography bib2 = new Bibliography(uuid: "2", text: "two")
        Profile profile = new Profile(opus: opus, scientificName: "sciName", bibliography: [bib1, bib2])
        save profile

        when: "the incoming data the same"
        service.saveBibliography(profile, [bibliography: [[uuid: "1", text: "one", order: 1], [uuid: "2", text: "two", order: 2]]])

        then: "there should be no change"
        profile.bibliography.each { it.id == bib1.id || it.id == bib2.id }
    }

    def "saveBibliography should change the bibliography the incoming data contains existing and new records"() {
        given:
        Opus opus = new Opus(glossary: new Glossary(), dataResourceUid: "dr1234", title: "title")
        save opus
        Bibliography bib1 = new Bibliography(uuid: "1", text: "one")
        Bibliography bib2 = new Bibliography(uuid: "2", text: "two")
        Profile profile = new Profile(opus: opus, scientificName: "sciName", bibliography: [bib1, bib2])
        save profile

        when: "the incoming data contains existing and new records"
        service.saveBibliography(profile, [bibliography: [[text: "four"], [uuid: "1", text: "one"]]])

        then: "the profile's list should be updated "
        profile.bibliography.each { it.id == bib1.id || it.text == "four" }
    }

    def "saveBibliography should change the bibliography the incoming data contains different records"() {
        given:
        Opus opus = new Opus(glossary: new Glossary(), dataResourceUid: "dr1234", title: "title")
        save opus
        Bibliography bib1 = new Bibliography(uuid: "1", text: "one")
        Bibliography bib2 = new Bibliography(uuid: "2", text: "two")
        Profile profile = new Profile(opus: opus, scientificName: "sciName", bibliography: [bib1, bib2])
        save profile

        when: "the incoming value is different"
        service.saveBibliography(profile, [bibliography: [[text: "four"]]])

        then: "the profile should be replaced"
        profile.bibliography.size() == 1
        profile.bibliography[0].text == "four"
    }

    def "saveBibliography should not change the bibliography if the incoming data contains the same records"() {
        given:
        Opus opus = new Opus(glossary: new Glossary(), dataResourceUid: "dr1234", title: "title")
        save opus
        Bibliography bib1 = new Bibliography(uuid: "1", text: "one")
        Bibliography bib2 = new Bibliography(uuid: "2", text: "two")
        Profile profile = new Profile(opus: opus, scientificName: "sciName", bibliography: [bib1, bib2])
        save profile

        when: "the incoming data the same"
        service.saveBibliography(profile, [bibliography: [[uuid: "1", text: "one", order: 1], [uuid: "2", text: "two", order: 2]]])

        then: "there should be no change"
        profile.bibliography.every { it.id == bib1.id || it.id == bib2.id }
    }

    def "saveBibliography should clear the bibliography if the incoming data is empty"() {
        given:
        Opus opus = new Opus(glossary: new Glossary(), dataResourceUid: "dr1234", title: "title")
        save opus
        Bibliography bib1 = new Bibliography(uuid: "1", text: "one")
        Bibliography bib2 = new Bibliography(uuid: "2", text: "two")
        Profile profile = new Profile(opus: opus, scientificName: "sciName", bibliography: [bib1, bib2])
        save profile

        when: "the incoming attribute is empty"
        service.saveBibliography(profile, [bibliography: []])

        then: "all existing bibliography should be removed"
        profile.bibliography.isEmpty()
    }

    def "saveBibliography should clear the bibliography if the incoming data is null"() {
        given:
        Opus opus = new Opus(glossary: new Glossary(), dataResourceUid: "dr1234", title: "title")
        save opus
        Bibliography bib1 = new Bibliography(uuid: "1", text: "one")
        Bibliography bib2 = new Bibliography(uuid: "2", text: "two")
        Profile profile = new Profile(opus: opus, scientificName: "sciName", bibliography: [bib1, bib2])
        save profile

        when: "the incoming attribute is empty"
        service.saveBibliography(profile, [bibliography: null])

        then: "all existing bibliography should be removed"
        profile.bibliography.isEmpty()
    }

    def "saveBibliography should update the profile draft if one exists"() {
        given:
        Opus opus = new Opus(glossary: new Glossary(), dataResourceUid: "dr1234", title: "title")
        save opus
        Bibliography bib1 = new Bibliography(uuid: "1", text: "one")
        Bibliography bib2 = new Bibliography(uuid: "2", text: "two")
        Profile profile = new Profile(opus: opus, scientificName: "sciName", bibliography: [bib1, bib2], draft: [uuid: "123", scientificName: "asd", bibliography: [bib1, bib2]])
        save profile

        when: "the incoming data the same"
        service.saveBibliography(profile, [bibliography: [[uuid: "3", text: "three", order: 1], [uuid: "4", text: "four", order: 2]]])

        then: "there should be no change"
        profile.bibliography.every { it.id == bib1.id || it.id == bib2.id }
        profile.draft.bibliography.every { it.text == "three" || it.text == "four" }
    }

    def "saveBHLLinks should throw an IllegalArgumentException if the profile id or data are not provided"() {
        when:
        service.saveBHLLinks(null, [a: "b"])

        then:
        thrown IllegalArgumentException

        when:
        service.saveBHLLinks("id", [:])

        then:
        thrown IllegalArgumentException
    }

    def "saveBHLLinks should throw an IllegalStateException if the profile does not exist"() {
        when:
        service.saveBHLLinks("unknown", [a: "b"])

        then:
        thrown IllegalStateException
    }

    def "saveBHLLinks should remove all links if the incoming list is empty"() {
        given:
        Opus opus = new Opus(glossary: new Glossary(), dataResourceUid: "dr1234", title: "title")
        save opus
        Link link1 = new Link(uuid: "1", url: "one", description: "desc", title: "title")
        Link link2 = new Link(uuid: "2", url: "two", description: "desc", title: "title")
        Profile profile = new Profile(opus: opus, scientificName: "sciName", bhlLinks: [link1, link2])
        save profile

        when:
        service.saveBHLLinks(profile.uuid, [links: []])

        then:
        profile.bhlLinks.isEmpty()
        Link.count() == 0
    }

    def "saveBHLLinks should replace all links if the incoming list contains all new elements"() {
        given:
        Opus opus = new Opus(glossary: new Glossary(), dataResourceUid: "dr1234", title: "title")
        save opus
        Link link1 = new Link(uuid: "1", url: "one", description: "desc", title: "title")
        Link link2 = new Link(uuid: "2", url: "two", description: "desc", title: "title")
        Profile profile = new Profile(opus: opus, scientificName: "sciName", bhlLinks: [link1, link2])
        save profile

        when:
        service.saveBHLLinks(profile.uuid, [links: [[url: "three"], [url: "four"]]])

        then:
        profile.bhlLinks.every { it.url == "three" || it.url == "four" }
    }

    def "saveBHLLinks should combine all links if the incoming list contains new and existing elements"() {
        given:
        Opus opus = new Opus(glossary: new Glossary(), dataResourceUid: "dr1234", title: "title")
        save opus
        Link link1 = new Link(uuid: "1", url: "one", description: "desc", title: "title")
        Link link2 = new Link(uuid: "2", url: "two", description: "desc", title: "title")
        Profile profile = new Profile(opus: opus, scientificName: "sciName", bhlLinks: [link1, link2])
        save profile

        when:
        service.saveBHLLinks(profile.uuid, [links: [[url: "one", uuid: "1"], [url: "four"]]])

        then:
        profile.bhlLinks.every { it.url == "one" || it.url == "four" }
    }

    def "saveBHLLinks should update the profile draft if one exists"() {
        given:
        Opus opus = new Opus(glossary: new Glossary(), dataResourceUid: "dr1234", title: "title")
        save opus
        Link link1 = new Link(uuid: "1", url: "one", description: "desc", title: "title")
        Link link2 = new Link(uuid: "2", url: "two", description: "desc", title: "title")
        Profile profile = new Profile(opus: opus, scientificName: "sciName", bhlLinks: [link1, link2], draft: [uuid: "123", scientificName: "sciName", bhlLinks: [link1, link2]])
        save profile

        when:
        service.saveBHLLinks(profile.uuid, [links: [[url: "three"], [url: "four"]]])

        then:
        profile.bhlLinks.every { it.url == "one" || it.url == "two" }
        profile.draft.bhlLinks.every { it.url == "three" || it.url == "four" }
    }

    def "saveLinks should throw an IllegalArgumentException if the profile id or data are not provided"() {
        when:
        service.saveLinks(null, [a: "b"])

        then:
        thrown IllegalArgumentException

        when:
        service.saveLinks("id", [:])

        then:
        thrown IllegalArgumentException
    }

    def "saveLinks should throw an IllegalStateException if the profile does not exist"() {
        when:
        service.saveLinks("unknown", [a: "b"])

        then:
        thrown IllegalStateException
    }

    def "saveLinks should remove all links if the incoming list is empty"() {
        given:
        Opus opus = new Opus(glossary: new Glossary(), dataResourceUid: "dr1234", title: "title")
        save opus
        Link link1 = new Link(uuid: "1", url: "one", description: "desc", title: "title")
        Link link2 = new Link(uuid: "2", url: "two", description: "desc", title: "title")
        Profile profile = new Profile(opus: opus, scientificName: "sciName", links: [link1, link2])
        save profile

        when:
        service.saveLinks(profile.uuid, [links: []])

        then:
        profile.links.isEmpty()
        Link.count() == 0
    }

    def "saveLinks should replace all links if the incoming list contains all new elements"() {
        given:
        Opus opus = new Opus(glossary: new Glossary(), dataResourceUid: "dr1234", title: "title")
        save opus
        Link link1 = new Link(uuid: "1", url: "one", description: "desc", title: "title")
        Link link2 = new Link(uuid: "2", url: "two", description: "desc", title: "title")
        Profile profile = new Profile(opus: opus, scientificName: "sciName", links: [link1, link2])
        save profile

        when:
        service.saveLinks(profile.uuid, [links: [[url: "three"], [url: "four"]]])

        then:
        profile.links.every { it.url == "three" || it.url == "four" }
    }

    def "saveLinks should combine all links if the incoming list contains new and existing elements"() {
        given:
        Opus opus = new Opus(glossary: new Glossary(), dataResourceUid: "dr1234", title: "title")
        save opus
        Link link1 = new Link(uuid: "1", url: "one", description: "desc", title: "title")
        Link link2 = new Link(uuid: "2", url: "two", description: "desc", title: "title")
        Profile profile = new Profile(opus: opus, scientificName: "sciName", links: [link1, link2])
        save profile

        when:
        service.saveLinks(profile.uuid, [links: [[url: "one", uuid: "1"], [url: "four"]]])

        then:
        profile.links.every { it.url == "one" || it.url == "four" }
    }

    def "saveLinks should update the profile draft if one exists"() {
        given:
        Opus opus = new Opus(glossary: new Glossary(), dataResourceUid: "dr1234", title: "title")
        save opus
        Link link1 = new Link(uuid: "1", url: "one", description: "desc", title: "title")
        Link link2 = new Link(uuid: "2", url: "two", description: "desc", title: "title")
        Profile profile = new Profile(opus: opus, scientificName: "sciName", links: [link1, link2], draft: [uuid: "123", scientificName: "sciName", links: [link1, link2]])
        save profile

        when:
        service.saveLinks(profile.uuid, [links: [[url: "three"], [url: "four"]]])

        then:
        profile.links.every { it.url == "one" || it.url == "two" }
        profile.draft.links.every { it.url == "three" || it.url == "four" }
    }

    def "savePublication should throw an IllegalArgumentException if no profile id or file are provided"() {
        when:
        service.savePublication(null, Mock(MultipartFile))

        then:
        thrown IllegalArgumentException

        when:
        service.savePublication("a", null)

        then:
        thrown IllegalArgumentException
    }

    def "savePublication should throw IllegalStateException if the profile does not exist"() {
        when:
        service.savePublication("unknown", Mock(MultipartFile))

        then:
        thrown IllegalStateException
    }

    def "savePublication should create a new Publication record"() {
        given:
        Opus opus = new Opus(glossary: new Glossary(), dataResourceUid: "dr1234", title: "title")
        save opus
        Profile profile = new Profile(opus: opus, scientificName: "sciName")
        save profile
        MultipartFile mockFile = Mock(MultipartFile)

        when:
        service.savePublication(profile.uuid, mockFile)

        then:
        Profile.list().get(0).publications.size() == 1
        1 * mockFile.transferTo(_)
    }

    def "savePublication should assign a DOI to the new publication if the DoiService returns successfully"() {
        given:
        Opus opus = new Opus(glossary: new Glossary(), dataResourceUid: "dr1234", title: "title")
        save opus
        Profile profile = new Profile(opus: opus, scientificName: "sciName")
        save profile
        MultipartFile mockFile = Mock(MultipartFile)
        service.doiService = Mock(DoiService)
        service.doiService.mintDOI(_, _, _) >> [status: "success", doi: "12345/0987"]

        when:
        Publication pub = service.savePublication(profile.uuid, mockFile)

        then:
        Profile.list().get(0).publications.size() == 1
        pub.doi == "12345/0987"
    }

    def "savePublication should return an error if the DoiService fails"() {
        given:
        Opus opus = new Opus(glossary: new Glossary(), dataResourceUid: "dr1234", title: "title")
        save opus
        Profile profile = new Profile(opus: opus, scientificName: "sciName")
        save profile
        MultipartFile mockFile = Mock(MultipartFile)
        service.doiService = Mock(DoiService)
        service.doiService.mintDOI(_, _, _) >> [status: "error", statusCode: "E001", message: "Something blew up!!"]

        when:
        def result = service.savePublication(profile.uuid, mockFile)

        then:
        Profile.list().get(0).publications.size() == 0
        result == [status: "error", statusCode: "E001", message: "Something blew up!!"]
    }

    def "deleteAttribute should throw IllegalStateException if no attributeId or profileId are provided"() {
        when:
        service.deleteAttribute(null, "profileId")

        then:
        thrown IllegalArgumentException

        when:
        service.deleteAttribute("attrId", null)

        then:
        thrown IllegalArgumentException
    }

    def "deleteAttribute should throw IllegalStateException if the attribute does not exist"() {
        given:
        Opus opus = new Opus(glossary: new Glossary(), dataResourceUid: "dr1234", title: "title")
        save opus
        Profile profile = new Profile(opus: opus, scientificName: "sciName")
        save profile

        when:
        service.deleteAttribute("unknown", profile.uuid)

        then:
        thrown IllegalStateException
    }

    def "deleteAttribute should throw IllegalStateException if the profile does not exist"() {
        given:
        Opus opus = new Opus(glossary: new Glossary(), dataResourceUid: "dr1234", title: "title")
        save opus
        Profile profile = new Profile(opus: opus, scientificName: "sciName")
        save profile
        Vocab vocab = new Vocab(name: "vocab1")
        Term term = new Term(uuid: "1", name: "title")
        vocab.addToTerms(term)
        save vocab
        Attribute attribute = new Attribute(uuid: "1", title: term, text: "text")
        profile.addToAttributes(attribute)
        save profile

        when:
        service.deleteAttribute(attribute.uuid, "unknown")

        then:
        thrown IllegalStateException
    }

    def "deleteAttribute should remove the attribute record"() {
        given:
        Opus opus = new Opus(glossary: new Glossary(), dataResourceUid: "dr1234", title: "title")
        save opus
        Profile profile = new Profile(opus: opus, scientificName: "sciName")
        save profile
        Vocab vocab = new Vocab(name: "vocab1")
        Term term = new Term(uuid: "1", name: "title")
        vocab.addToTerms(term)
        save vocab
        Attribute attribute = new Attribute(uuid: "1", title: term, text: "text", profile: profile, contributors: [])
        profile.addToAttributes(attribute)
        save attribute
        save profile

        expect:
        Attribute.count() == 1
        profile.attributes.size() == 1

        when:
        service.deleteAttribute(attribute.uuid, profile.uuid)

        then:
        Attribute.count() == 0
    }

    def "deleteAttribute should update the profile draft if one exists"() {
        given:
        Opus opus = new Opus(glossary: new Glossary(), dataResourceUid: "dr1234", title: "title")
        save opus
        Profile profile = new Profile(opus: opus, scientificName: "sciName", draft: [uuid: "123", scientificName: "sciName"])
        save profile
        Vocab vocab = new Vocab(name: "vocab1")
        Term term = new Term(uuid: "1", name: "title")
        vocab.addToTerms(term)
        save vocab
        Attribute attribute = new Attribute(uuid: "1", title: term, text: "text", profile: profile)
        profile.addToAttributes(attribute)
        profile.draft.attributes = [new Attribute(uuid: "1", title: term, text: "text")]
        save attribute
        save profile

        expect:
        Attribute.count() == 1
        profile.attributes.size() == 1

        when:
        service.deleteAttribute(attribute.uuid, profile.uuid)

        then:
        Attribute.count() == 1
        profile.attributes.size() == 1
        profile.draft.attributes.size() == 0
    }

    def "updateAttribute should throw IllegalArgumentException if no attribute id, profile id or data are provided"() {
        when:
        service.updateAttribute(null, "p", [a: "b"])

        then:
        thrown IllegalArgumentException

        when:
        service.updateAttribute("a", null, [a: "b"])

        then:
        thrown IllegalArgumentException

        when:
        service.updateAttribute("a", "p", [:])

        then:
        thrown IllegalArgumentException
    }

    def "updateAttribute should throw IllegalStateException if the attribute does not exist"() {
        given:
        Opus opus = new Opus(glossary: new Glossary(), dataResourceUid: "dr1234", title: "title")
        save opus
        Profile profile = new Profile(opus: opus, scientificName: "sciName")
        save profile
        Vocab vocab = new Vocab(name: "vocab1")
        Term term = new Term(uuid: "1", name: "title")
        vocab.addToTerms(term)
        save vocab
        Attribute attribute = new Attribute(uuid: "1", title: term, text: "text", profile: profile, contributors: [])
        profile.addToAttributes(attribute)
        save attribute
        save profile

        when:
        service.updateAttribute("unknown", profile.uuid, [a: "b"])

        then:
        thrown IllegalStateException
    }

    def "updateAttribute should throw IllegalStateException if the profile does not exist"() {
        given:
        Vocab vocab = new Vocab(name: "vocab1")
        Term term = new Term(uuid: "1", name: "title")
        vocab.addToTerms(term)
        save vocab
        Opus opus = new Opus(glossary: new Glossary(), dataResourceUid: "dr1234", title: "title")
        save opus
        Profile profile = new Profile(opus: opus, scientificName: "sciName")
        save profile
        Attribute attribute = new Attribute(uuid: "1", title: term, text: "text", profile: profile, contributors: [])
        profile.addToAttributes(attribute)
        save attribute
        save profile

        when:
        service.updateAttribute(attribute.uuid, "unknown", [a: "b"])

        then:
        thrown IllegalStateException
    }

    def "updateAttribute should update the attribute fields"() {
        given:
        Vocab vocab = new Vocab(name: "vocab1")
        Term term1 = new Term(uuid: "1", name: "title1")
        Term term2 = new Term(uuid: "2", name: "title2")
        vocab.addToTerms(term1)
        vocab.addToTerms(term2)
        save vocab
        Opus opus = new Opus(glossary: new Glossary(), dataResourceUid: "dr1234", title: "title", attributeVocabUuid: vocab.uuid)
        save opus
        Profile profile = new Profile(opus: opus, scientificName: "sciName")
        save profile
        Attribute attribute = new Attribute(uuid: "1", title: term1, text: "text", profile: profile, contributors: [])
        profile.addToAttributes(attribute)
        save attribute
        save profile
        Contributor contributor = new Contributor(userId: "123", name: "fred")
        save contributor

        service.vocabService = new VocabService()

        when:
        service.updateAttribute(attribute.uuid, profile.uuid, [title: "title2", text: "updatedText", userId: "123", significantEdit: true])

        then:
        Attribute a = Attribute.list()[0]
        a.title == term2
        a.text == "updatedText"
        a.editors == [contributor] as Set
    }

    def "updateAttribute should update profile draft if one exists"() {
        given:
        Vocab vocab = new Vocab(name: "vocab1")
        Term term1 = new Term(uuid: "1", name: "title1")
        Term term2 = new Term(uuid: "2", name: "title2")
        vocab.addToTerms(term1)
        vocab.addToTerms(term2)
        save vocab
        Opus opus = new Opus(glossary: new Glossary(), dataResourceUid: "dr1234", title: "title", attributeVocabUuid: vocab.uuid)
        save opus
        Profile profile = new Profile(opus: opus, scientificName: "sciName", draft: [uuid: "123", scientificName: "sciName"])
        save profile
        Attribute attribute = new Attribute(uuid: "1", title: term1, text: "text", profile: profile)
        profile.addToAttributes(attribute)
        profile.draft.attributes = [new Attribute(uuid: "1", title: term1, text: "text")]
        save attribute
        save profile

        service.vocabService = new VocabService()

        when:
        service.updateAttribute(attribute.uuid, profile.uuid, [text: "updatedText"])

        then:
        Attribute.list()[0].text == "text"
        profile.draft.attributes[0].text == "updatedText"
    }

    def "createAttribute should throw IllegalArgumentException if no profile id or data are provided"() {
        when:
        service.createAttribute(null, [a: "b"])

        then:
        thrown IllegalArgumentException

        when:
        service.createAttribute("p", [:])

        then:
        thrown IllegalArgumentException
    }


    def "createAttribute should throw IllegalStateException if the profile does not exist"() {
        given:
        Vocab vocab = new Vocab(name: "vocab1")
        Term term = new Term(uuid: "1", name: "title")
        vocab.addToTerms(term)
        save vocab
        Opus opus = new Opus(glossary: new Glossary(), dataResourceUid: "dr1234", title: "title")
        save opus
        Profile profile = new Profile(opus: opus, scientificName: "sciName")
        save profile

        when:
        service.createAttribute("unknown", [a: "b"])

        then:
        thrown IllegalStateException
    }

    def "createAttribute should update the attribute fields"() {
        given:
        Vocab vocab = new Vocab(name: "vocab1")
        Term term = new Term(uuid: "1", name: "title")
        vocab.addToTerms(term)
        save vocab
        Opus opus = new Opus(glossary: new Glossary(), dataResourceUid: "dr1234", title: "title", attributeVocabUuid: vocab.uuid)
        save opus
        Profile profile = new Profile(opus: opus, scientificName: "sciName")
        save profile
        Attribute attribute = new Attribute(uuid: "1", title: term, text: "text", profile: profile, contributors: [])
        profile.addToAttributes(attribute)
        save attribute
        save profile
        Contributor fred = new Contributor(userId: "123", name: "fred")
        Contributor bob = new Contributor(userId: "987", name: "bob")
        save fred
        save bob

        service.vocabService = new VocabService()

        when:
        service.createAttribute(profile.uuid, [title: "title", text: "updatedText", userId: "123", editors: ["bob"]])

        then:
        Attribute.count() == 2
        Attribute a = Attribute.list()[1]
        a.title == term
        a.creators.size() == 1
        a.creators[0].uuid == fred.uuid
        a.editors.size() == 1
        a.editors[0].uuid == bob.uuid
        a.text == "updatedText"
        a.original == null
    }

    def "createAttribute should update the profile draft if one exists"() {
        given:
        Vocab vocab = new Vocab(name: "vocab1")
        Term term = new Term(uuid: "1", name: "title")
        vocab.addToTerms(term)
        save vocab
        Opus opus = new Opus(glossary: new Glossary(), dataResourceUid: "dr1234", title: "title", attributeVocabUuid: vocab.uuid)
        save opus
        Profile profile = new Profile(opus: opus, scientificName: "sciName", draft: [uuid: "123", scientificName: "sciName"])
        save profile

        service.vocabService = new VocabService()

        when:
        service.createAttribute(profile.uuid, [title: "title", text: "text", userId: "123", editors: ["bob"]])

        then:
        Attribute.count() == 0 // should not be persisted while in draft
        profile.attributes == null || profile.attributes.size() == 0
        profile.draft.attributes.size() == 1
        profile.draft.attributes[0].text == "text"
    }

    def "createAttribute should sanitize HTML"() {
        given:
        Vocab vocab = new Vocab(name: "vocab1")
        Term term = new Term(uuid: "1", name: "title")
        vocab.addToTerms(term)
        save vocab
        Opus opus = new Opus(glossary: new Glossary(), dataResourceUid: "dr1234", title: "title", attributeVocabUuid: vocab.uuid)
        save opus
        Profile profile = new Profile(opus: opus, scientificName: "sciName", uuid: "123")
        save profile

        service.vocabService = new VocabService()

        when:
        service.createAttribute(profile.uuid, [title: "title", text: "<p>text</p><script>alert('me');</script>", userId: "123", editors: ["bob"]])

        then:
        profile.attributes.size() == 1
        profile.attributes[0].text == "<p>text</p>"

    }

    def "createAttribute should not update the creator but should set the original attribute when there is an original attribute in the incoming data"() {
        given:
        Vocab vocab = new Vocab(name: "vocab1")
        Term term = new Term(uuid: "1", name: "title")
        vocab.addToTerms(term)
        save vocab
        Opus opus = new Opus(glossary: new Glossary(), dataResourceUid: "dr1234", title: "title", attributeVocabUuid: vocab.uuid)
        save opus
        Profile profile = new Profile(opus: opus, scientificName: "sciName")
        save profile
        Attribute attribute = new Attribute(uuid: "1", title: term, text: "text", profile: profile, contributors: [])
        profile.addToAttributes(attribute)
        save attribute
        save profile
        Contributor fred = new Contributor(userId: "123", name: "fred")
        Contributor bob = new Contributor(userId: "987", name: "bob")
        save fred
        save bob

        service.vocabService = new VocabService()

        when:
        service.createAttribute(profile.uuid, [title: "title", text: "updatedText", userId: "123", editors: ["bob"], original: [uuid: "1"]])

        then:
        Attribute.count() == 2
        Attribute a = Attribute.list()[1]
        a.creators.size() == 0 // the creator should not be set when there is an 'original' attribute (i.e. this attribute copied from another profile
        a.original == attribute
    }

    def "getOrCreateContributor should match on userId if provided"() {
        given:
        Contributor fred = new Contributor(userId: "123", name: "fred")
        Contributor bob = new Contributor(userId: "987", name: "bob")
        save fred
        save bob

        when:
        Contributor result = service.getOrCreateContributor("bob", "123")

        then:
        result == fred
    }

    def "getOrCreateContributor should match on name if user id is not provided"() {
        given:
        Contributor fred = new Contributor(userId: "123", name: "fred")
        Contributor bob = new Contributor(userId: "987", name: "bob")
        save fred
        save bob

        when:
        Contributor result = service.getOrCreateContributor("bob", null)

        then:
        result == bob
    }

    def "getOrCreateContributor should create a new Contributor if no match is found on userId or name"() {
        given:
        Contributor fred = new Contributor(userId: "123", name: "fred")
        Contributor bob = new Contributor(userId: "987", name: "bob")
        save fred
        save bob

        when:
        Contributor result = service.getOrCreateContributor("jill", null)

        then:
        result.name == "jill"
        Contributor.count() == 3
    }

    def "saveAuthorship should not change the authorship if the incoming data does not have the authorship attribute"() {
        given:
        Opus opus = new Opus(glossary: new Glossary(), dataResourceUid: "dr1234", title: "title")
        save opus
        Authorship auth1 = new Authorship(category: new Term(name: "Acknowledgement"), text: "Fred, Jill")
        Authorship auth2 = new Authorship(category: new Term(name: "Author"), text: "Bob, Jane")
        Profile profile = new Profile(opus: opus, scientificName: "sciName", authorship: [auth1, auth2])
        save profile

        when: "the incoming data does not have the attribute"
        service.saveAuthorship(profile.uuid, [a: "bla"])

        then: "there should be no change"
        profile.authorship.contains(auth1) && profile.authorship.contains(auth2)
    }

    def "saveAuthorship should change the authorship the incoming data contains existing and new records"() {
        given:
        Opus opus = new Opus(glossary: new Glossary(), dataResourceUid: "dr1234", title: "title")
        save opus
        Authorship auth1 = new Authorship(category: new Term(name: "Acknowledgement"), text: "Fred, Jill")
        Authorship auth2 = new Authorship(category: new Term(name: "Author"), text: "Bob, Jane")
        Profile profile = new Profile(opus: opus, scientificName: "sciName", authorship: [auth1, auth2])
        save profile

        when: "the incoming data contains existing and new records"
        service.saveAuthorship(profile.uuid, [authorship: [[text: "Sarah", category: "Editor"], [category: "Author", text: "Fred, Jill"]]])

        then: "the profile's list should be updated "
        profile.authorship.each { it.text == auth1.text || it.text == "Sarah" }
    }

    def "saveAuthorship should change the authorship if the incoming data contains different records"() {
        given:
        Opus opus = new Opus(glossary: new Glossary(), dataResourceUid: "dr1234", title: "title")
        save opus
        Authorship auth1 = new Authorship(category: new Term(name: "Acknowledgement"), text: "Fred, Jill")
        Authorship auth2 = new Authorship(category: new Term(name: "Author"), text: "Bob, Jane")
        Profile profile = new Profile(opus: opus, scientificName: "sciName", authorship: [auth1, auth2])
        save profile

        when: "the incoming value is different"
        service.saveAuthorship(profile.uuid, [authorship: [[text: "Sarah", category: "Author"]]])

        then: "the profile should be replaced"
        profile.authorship.size() == 1
        profile.authorship[0].text == "Sarah"
        profile.authorship[0].category.name == "Author"
    }

    def "saveAuthorship should not change the authorship if the incoming data contains the same records"() {
        given:
        Opus opus = new Opus(glossary: new Glossary(), dataResourceUid: "dr1234", title: "title")
        save opus
        Authorship auth1 = new Authorship(category: new Term(name: "Acknowledgement"), text: "Fred, Jill")
        Authorship auth2 = new Authorship(category: new Term(name: "Author"), text: "Bob, Jane")
        Profile profile = new Profile(opus: opus, scientificName: "sciName", authorship: [auth1, auth2])
        save profile

        when: "the incoming data the same"
        service.saveAuthorship(profile.uuid, [authorship: [[text: "Bob, Jane", category: "Author"], [text: "Fred, Jill", category: "Acknowledgement"]]])

        then: "there should be no change"
        profile.authorship.size() == 2
        profile.authorship.every { it.text == auth1.text || it.text == auth2.text }
    }

    def "saveAuthorship should clear the authorship if the incoming data is empty"() {
        given:
        Opus opus = new Opus(glossary: new Glossary(), dataResourceUid: "dr1234", title: "title")
        save opus
        Authorship auth1 = new Authorship(category: new Term(name: "Acknowledgement"), text: "Fred, Jill")
        Authorship auth2 = new Authorship(category: new Term(name: "Author"), text: "Bob, Jane")
        Profile profile = new Profile(opus: opus, scientificName: "sciName", authorship: [auth1, auth2])
        save profile

        when: "the incoming attribute is empty"
        service.saveAuthorship(profile.uuid, [authorship: []])

        then: "all existing authorship should be removed"
        profile.authorship.isEmpty()
    }

    def "saveAuthorship should clear the authorship if the incoming data is null"() {
        given:
        Opus opus = new Opus(glossary: new Glossary(), dataResourceUid: "dr1234", title: "title")
        save opus
        Authorship auth1 = new Authorship(category: new Term(name: "Acknowledgement"), text: "Fred, Jill")
        Authorship auth2 = new Authorship(category: new Term(name: "Author"), text: "Bob, Jane")
        Profile profile = new Profile(opus: opus, scientificName: "sciName", authorship: [auth1, auth2])
        save profile

        when: "the incoming attribute is empty"
        service.saveAuthorship(profile.uuid, [authorship: null])

        then: "all existing authorship should be removed"
        profile.authorship.isEmpty()
    }

    def "saveAuthorship should update the profile draft if one exists"() {
        given:
        Opus opus = new Opus(glossary: new Glossary(), dataResourceUid: "dr1234", title: "title")
        save opus
        Authorship auth1 = new Authorship(category: new Term(name: "Author"), text: "Bob, Jane")
        Profile profile = new Profile(opus: opus, scientificName: "sciName", authorship: [auth1], draft: [uuid: "123", scientificName: "sciName", authorship: [auth1]])
        save profile

        when: "the incoming value is different"
        service.saveAuthorship(profile.uuid, [authorship: [[text: "Sarah", category: "Author"]]])

        then: "the profile should be replaced"
        profile.authorship.size() == 1
        profile.authorship[0].text == "Bob, Jane"
        profile.authorship[0].category.name == "Author"
        profile.draft.authorship.size() == 1
        profile.draft.authorship[0].text == "Sarah"
        profile.draft.authorship[0].category.name == "Author"
    }

    def "toggleDraftMode should create a new draft Profile if one does not exist"() {
        given:
        Opus opus = save new Opus(glossary: new Glossary(), dataResourceUid: "dr1234", title: "title")
        Profile profile = save new Profile(opus: opus, scientificName: "sciName")

        expect:
        Profile.count == 1

        when:
        service.toggleDraftMode(profile.uuid)

        then:
        Profile.count == 1
        profile.draft != null
        profile.draft != profile
        profile.draft.id != profile.id
        profile.draft.uuid == profile.uuid
    }

    def "toggleDraftMode should update the current Profile record with the draft Profile's details if one exists"() {
        given:
        Opus opus = save new Opus(glossary: new Glossary(), dataResourceUid: "dr1234", title: "title")
        Profile profile = save new Profile(uuid: "uuid1", opus: opus, scientificName: "sciName", draft: new DraftProfile(opus: opus, scientificName: "sciNameDraft", uuid: "uuid1"))

        expect:
        Profile.count == 1

        when:
        service.toggleDraftMode(profile.uuid, true)

        then:
        Profile.count == 1
        Profile newProfile = Profile.list().get(0)
        newProfile.draft == null
        newProfile.id == profile.id
        newProfile.uuid == profile.uuid
        newProfile.scientificName == "sciNameDraft"
    }

    def "toggleDraftModel should delete attributes that exist on the profile but not in the draft"() {
        // i.e. they have been deleted from the draft
        given:
        Vocab vocab = new Vocab(name: "vocab1")
        Term term1 = new Term(uuid: "1", name: "title1")
        Term term2 = new Term(uuid: "2", name: "title2")
        vocab.addToTerms(term1)
        vocab.addToTerms(term2)
        save vocab
        Opus opus = save new Opus(glossary: new Glossary(), dataResourceUid: "dr1234", title: "title", attributeVocabUuid: vocab.uuid)
        Profile profile = save new Profile(uuid: "uuid1", opus: opus, scientificName: "sciName")
        Attribute attribute1 = save new Attribute(uuid: "uuid1", title: term1, text: "text1", profile: profile)
        Attribute attribute2 = save new Attribute(uuid: "uuid2", title: term2, text: "text2", profile: profile)

        service.toggleDraftMode(profile.uuid)

        profile.draft.attributes.remove(0)

        save profile

        expect:
        Attribute.count == 2
        Profile.count == 1
        Profile.list()[0].attributes.size() == 2

        when:
        service.toggleDraftMode(profile.uuid, true)

        then:
        Attribute.count == 1
        Attribute.list()[0] == attribute2
    }

    def "toggleDraftMode should remove the files for any attachment deleted during draft mode"() {
        given:
        Opus opus = save new Opus(glossary: new Glossary(), dataResourceUid: "dr1234", title: "title")
        Profile profile = save new Profile(uuid: "uuid1", opus: opus, scientificName: "sciName", attachments: [new Attachment(uuid: "1234", title: "title")])
        profile.draft = new DraftProfile(uuid: "uuid1", scientificName: "sciName", attachments: [])
        save profile

        when:
        service.toggleDraftMode(profile.uuid, true)

        then:
        1 * service.attachmentService.deleteAttachment(opus.uuid, profile.uuid, "1234", _)
    }

    def "discardDraftChanges should delete the file for any attachment uploaded during draft mode"() {
        given:
        Opus opus = save new Opus(glossary: new Glossary(), dataResourceUid: "dr1234", title: "title")
        Profile profile = save new Profile(uuid: "uuid1", opus: opus, scientificName: "sciName", attachments: [])
        profile.draft = new DraftProfile(uuid: "uuid1", scientificName: "sciName", attachments: [new Attachment(uuid: "1234", title: "title")])
        save profile

        when:
        service.discardDraftChanges(profile.uuid)

        then:
        1 * service.attachmentService.deleteAttachment(opus.uuid, profile.uuid, "1234", _)
    }

    def "deleteAttachment should remove the attachment entity and the file when there is a filename"() {
        given:
        Opus opus1 = new Opus(title: "opus1", dataResourceUid: "123", glossary: new Glossary())
        save opus1
        Profile profile = new Profile(opus: opus1, scientificName: "profile1", attachments: [new Attachment(uuid: "1234", filename: "file1")])
        save profile

        when:
        service.deleteAttachment(profile.uuid, "1234")

        then:
        profile.attachments.isEmpty()
        1 * service.attachmentService.deleteAttachment(opus1.uuid, profile.uuid, "1234", _)
    }

    def "deleteAttachment should not attempt to remove a file when there is no filename"() {
        given:
        Opus opus1 = new Opus(title: "opus1", dataResourceUid: "123", glossary: new Glossary())
        save opus1
        Profile profile = new Profile(opus: opus1, scientificName: "profile1", attachments: [new Attachment(uuid: "1234", url: "url")])
        save profile

        when:
        service.deleteAttachment(profile.uuid, "1234")

        then:
        profile.attachments.isEmpty()
        0 * service.attachmentService.deleteAttachment(opus1.uuid, profile.uuid, "1234", _)
    }

    def "deleteAttachment should update the draft and not delete the file if the profile is in draft mode"() {
        given:
        Opus opus1 = new Opus(title: "opus1", dataResourceUid: "123", glossary: new Glossary())
        save opus1
        Profile profile = new Profile(opus: opus1, scientificName: "profile1", attachments: [new Attachment(uuid: "1234")])
        save profile
        profile.draft = new DraftProfile(uuid: profile.uuid, scientificName: "profile1", attachments: [new Attachment(uuid: "1234")])
        save profile

        when:
        service.deleteAttachment(profile.uuid, "1234")

        then:
        profile.attachments.size() == 1
        profile.draft.attachments.isEmpty()
        0 * service.attachmentService.deleteAttachment(opus1.uuid, profile.uuid, "1234", _)
    }

    def "deleteAttachment should do nothing if there are no attachments"() {
        given:
        Opus opus1 = new Opus(title: "opus1", dataResourceUid: "123", glossary: new Glossary())
        save opus1
        Profile profile = new Profile(opus: opus1, scientificName: "profile1", attachments: [])
        save profile

        when:
        service.deleteAttachment(profile.uuid, "1234")

        then:
        !profile.attachments
        0 * service.attachmentService.deleteAttachment(_, _, _, _)
    }

    def "saveAttachment should update an existing attachment if there is uuid"() {
        given:
        Opus opus1 = new Opus(title: "opus1", dataResourceUid: "123", glossary: new Glossary())
        save opus1
        Profile profile = new Profile(opus: opus1, scientificName: "profile1", attachments: [new Attachment(uuid: "1234", title: "oldTitle", description: "oldDesc")])
        save profile

        when:
        service.saveAttachment(profile.uuid, [uuid: "1234", title: "newTitle"], null)

        then:
        profile.attachments.size() == 1
        profile.attachments[0].title == "newTitle"
        0 * service.attachmentService.saveAttachment(_, _, _, _, _)
        0 * service.attachmentService.deleteAttachment(_, _, _, _)
    }

    def "saveAttachment should create a new attachment if there is no uuid"() {
        given:
        Opus opus1 = new Opus(title: "opus1", dataResourceUid: "123", glossary: new Glossary())
        save opus1
        Profile profile = new Profile(opus: opus1, scientificName: "profile1", attachments: [new Attachment(uuid: "1234", title: "oldTitle", description: "oldDesc")])
        save profile

        when:
        service.saveAttachment(profile.uuid, [title: "newTitle"], Mock(CommonsMultipartFile))

        then:
        profile.attachments.size() == 2
        profile.attachments[0].title == "oldTitle"
        profile.attachments[1].title == "newTitle"
        1 * service.attachmentService.saveAttachment(_, _, _, _, _)
        0 * service.attachmentService.deleteAttachment(_, _, _, _)
    }

    def "saveAttachment should operate on the draft if the profile is in draft mode"() {
        given:
        Opus opus1 = new Opus(title: "opus1", dataResourceUid: "123", glossary: new Glossary())
        save opus1
        Profile profile = new Profile(opus: opus1, scientificName: "profile1", attachments: [new Attachment(uuid: "1234", title: "oldTitle", description: "oldDesc")])
        save profile
        profile.draft = new DraftProfile(uuid: profile.uuid, scientificName: "profile1", attachments: [new Attachment(uuid: "1234", title: "oldTitle", description: "oldDesc")])
        save profile

        when:
        service.saveAttachment(profile.uuid, [title: "newTitle"], Mock(CommonsMultipartFile))

        then:
        profile.attachments.size() == 1
        profile.draft.attachments.size() == 2
        profile.attachments[0].title == "oldTitle"
        profile.draft.attachments[0].title == "oldTitle"
        profile.draft.attachments[1].title == "newTitle"
        1 * service.attachmentService.saveAttachment(_, _, _, _, _)
        0 * service.attachmentService.deleteAttachment(_, _, _, _)
    }

    def "renameProfile should use the case of the matched scientific name if the supplied name doesn't have the same case"() {
        given:
        Opus opus1 = new Opus(title: "opus1", dataResourceUid: "123", glossary: new Glossary())
        save opus1
        Profile profile = new Profile(opus: opus1, uuid: "profile1", scientificName: "sciName", fullName: "sciName author", nameAuthor: "author")
        save profile

        service.nameService.matchName(_, _, _) >> [scientificName: "sciName", author: "fred", guid: "ABC", fullName: "sciName fred"]

        when:
        service.renameProfile("profile1", [newName: "SCINAME"])
        profile = Profile.findByUuid("profile1") // reload the profile

        then:

        profile.scientificName == "sciName"
        profile.fullName == "sciName fred"
        profile.guid == "ABC"
    }

    def "saving a profile without draft should set the lastPublished field"() {
        given:
        Date now = new Date()
        Opus opus1 = new Opus(title: "opus1", dataResourceUid: "123", glossary: new Glossary())
        save opus1
        Profile profile = new Profile(opus: opus1, scientificName: "profile1", attachments: [new Attachment(uuid: "1234", title: "oldTitle", description: "oldDesc")])
        save profile

        expect:
        profile.lastPublished > now
    }

    def "creating a draft profile should set the its lastPublished field to the same date as the profile"() {
        given:
        Opus opus1 = new Opus(title: "opus1", dataResourceUid: "123", glossary: new Glossary())
        save opus1
        Profile profile = new Profile(opus: opus1, scientificName: "profile1", attachments: [new Attachment(uuid: "1234", title: "oldTitle", description: "oldDesc")])
        save profile

        profile.draft = new DraftProfile(uuid: profile.uuid, scientificName: "profile1", attachments: [new Attachment(uuid: "1234", title: "oldTitle", description: "oldDesc")])
        save profile

        expect:
        profile.draft.lastPublished == profile.lastPublished
    }


    def "updating a draft profile should set the lastPublished field on the draft only"() {
        given:
        Opus opus1 = new Opus(title: "opus1", dataResourceUid: "123", glossary: new Glossary())
        save opus1
        Profile profile = new Profile(opus: opus1, scientificName: "profile1", attachments: [new Attachment(uuid: "1234", title: "oldTitle", description: "oldDesc")])
        save profile
        Date profileOriginalPublishedDate = profile.lastPublished

        profile.draft = new DraftProfile(uuid: profile.uuid, scientificName: "profile1", attachments: [new Attachment(uuid: "1234", title: "oldTitle", description: "oldDesc")])
        save profile

        profile.draft.fullName = "A new name"
        save profile

        expect:
        profile.draft.lastPublished > profile.lastPublished
        profile.lastPublished == profileOriginalPublishedDate
    }

    def "discarding a draft profile should not change the profile lastPublished field"() {
        given:
        Opus opus1 = new Opus(title: "opus1", dataResourceUid: "123", glossary: new Glossary())
        save opus1
        Profile profile = new Profile(opus: opus1, scientificName: "profile1", attachments: [new Attachment(uuid: "1234", title: "oldTitle", description: "oldDesc")])
        save profile
        Date profileOriginalPublishedDate = profile.lastPublished

        profile.draft = new DraftProfile(uuid: profile.uuid, scientificName: "profile1", attachments: [new Attachment(uuid: "1234", title: "oldTitle", description: "oldDesc")])
        save profile

        profile.draft = null
        save profile

        expect:
        profile.lastPublished == profileOriginalPublishedDate
    }


    def "publishing a draft profile should change the profile lastPublished field"() {
        given:
        Opus opus1 = new Opus(title: "opus1", dataResourceUid: "123", glossary: new Glossary())
        save opus1
        Profile profile = new Profile(opus: opus1, scientificName: "profile1", attachments: [new Attachment(uuid: "1234", title: "oldTitle", description: "oldDesc")])
        save profile
        Date profileOriginalPublishedDate = profile.lastPublished

        profile.draft = new DraftProfile(uuid: profile.uuid, scientificName: "profile1", attachments: [new Attachment(uuid: "1234", title: "oldTitle", description: "oldDesc")])
        save profile

        profile.draft = null
        profile.fullName = "An updated value copied from the draft"
        save profile

        expect:
        profile.lastPublished > profileOriginalPublishedDate
    }



    def "populateTaxonHierarchy should add a fully customised hierarchy as the classification for the profile, in reverse order"() {
        given:
        List customHierarchy = [
                [name: "profile", rank: "species"],
                [name: "parent", rank: "genus"],
                [name: "grandparent", rank: "family"],
                [name: "greatGrandparent", rank: "class"]
        ]
        Profile profile = new Profile()

        when:
        service.populateTaxonHierarchy(profile, customHierarchy)

        then:
        profile.classification.size() == 4
        profile.classification[0].name == "greatGrandparent"
        profile.classification[1].name == "grandparent"
        profile.classification[2].name == "parent"
        profile.classification[3].name == "profile"
    }

    def "populateTaxonHierarchy should set the profile's rank to the rank of the first item of the hierarchy"() {
        given:
        List customHierarchy = [
                [name: "profile", rank: "species"],
                [name: "parent", rank: "genus"],
                [name: "grandparent", rank: "family"],
                [name: "greatGrandparent", rank: "class"]
        ]
        Profile profile = new Profile()

        when:
        service.populateTaxonHierarchy(profile, customHierarchy)

        then:
        profile.rank == "species"
    }

    def "populateTaxonHierarchy should retrieve the classification from the bie service for an item with a GUID"() {
        given: "a hierarchy with the new profile and parent with a known name"
        List customHierarchy = [
                [name: "profile", rank: "species"],
                [name: "parent", rank: "genus", guid: "1234"]
        ]
        Profile profile = new Profile()

        when:
        service.populateTaxonHierarchy(profile, customHierarchy)

        then: "the classification should contain the hierarchy of the known name, plus the new profile"

        1 * bieService.getClassification(_) >> [
                [scientificName: "greatGrandparent", rank: "class", guid: "3"],
                [scientificName: "grandparent", rank: "family", guid: "2"],
                [scientificName: "parent", rank: "genus", guid: "1234"]
        ]

        profile.classification.size() == 4
        profile.classification[0].name == "greatGrandparent"
        profile.classification[0].guid == "3"
        profile.classification[1].name == "grandparent"
        profile.classification[1].guid == "2"
        profile.classification[2].name == "parent"
        profile.classification[2].guid == "1234"
        profile.classification[3].name == "profile"
    }

    def "populateTaxonHierarchy should retrieve the classification from the parent profile for an item with a profile id"() {
        given: "a hierarchy with the new profile and parent with a known profileid"
        Profile profile = new Profile()

        Opus opus1 = new Opus(title: "opus1", dataResourceUid: "123", glossary: new Glossary())
        save opus1
        Profile parentProfile = new Profile(opus: opus1, scientificName: "profile1", attachments: [new Attachment(uuid: "1234", title: "oldTitle", description: "oldDesc")])
        parentProfile.classification = [
                new Classification(name: "greatGrandparent", rank: "class", guid: "3"),
                new Classification(name: "grandparent", rank: "family", guid: "2"),
                new Classification(name: "parent", rank: "genus", guid: "1234")
        ]
        save parentProfile

        List customHierarchy = [
                [name: "profile", rank: "species"],
                [name: "parent", rank: "genus", guid: parentProfile.uuid]
        ]

        when:
        service.populateTaxonHierarchy(profile, customHierarchy)

        then: "the classification should contain the classification of the parent profile, plus the new profile"
        profile.classification.size() == 4
        profile.classification[0].name == "greatGrandparent"
        profile.classification[0].guid == "3"
        profile.classification[1].name == "grandparent"
        profile.classification[1].guid == "2"
        profile.classification[2].name == "parent"
        profile.classification[2].guid == "1234"
        profile.classification[3].name == "profile"
    }

    @Unroll
    def "setPrimaryMultimedia should update the correct profile with the correct UUID"() {
        given:
        Opus opus = new Opus(title: "opus1", dataResourceUid: "123", glossary: new Glossary())
        save opus
        Profile profile = new Profile(opus: opus)
        if (draft) {
            profile.draft = new DraftProfile(primaryAudio: startAudio, primaryVideo: startVideo)
        } else {
            profile.primaryAudio = startAudio
            profile.primaryVideo = startVideo
        }
        profile.save()

        def json = [:]
        if (newVideo) {
            json.primaryVideo = startVideo
        }
        if (newAudio) {
            json.primaryAudio = startAudio
        }

        when:
        service.setPrimaryMultimedia(profile, json)

        then:
        if (draft) {
            profile.draft.primaryAudio == newAudio
            profile.draft.primaryVideo == newVideo
            profile.primaryAudio == null
            profile.primaryVideo == null
        } else {
            profile.primaryAudio == newAudio
            profile.primaryVideo == newVideo
        }

        where:
        startAudio || startVideo || newAudio || newVideo || draft
        null | null | 'abc' | 'def' | false
        'def' | 'abc' | null | null | false
        null | null | 'abc' | 'def' | true
        'def' | 'abc' | null | null | true
    }
}


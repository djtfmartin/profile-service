package au.org.ala.profile.util

import au.org.ala.profile.Attachment
import au.org.ala.profile.Attribute
import au.org.ala.profile.Authorship
import au.org.ala.profile.Bibliography
import au.org.ala.profile.Classification
import au.org.ala.profile.Contributor
import au.org.ala.profile.DraftProfile
import au.org.ala.profile.ImageSettings
import au.org.ala.profile.Link
import au.org.ala.profile.Profile
import au.org.ala.profile.Publication
import au.org.ala.profile.Term
import spock.lang.Specification

import static au.org.ala.profile.util.ImageOption.EXCLUDE
import static au.org.ala.profile.util.ImageOption.INCLUDE

class CloneAndDraftUtilTest extends Specification {

    Profile original
    Profile profileWithDraft
    Contributor fred
    Contributor jill
    Contributor bob
    Attribute attribute1
    Attribute attribute2
    Attribute attribute1Draft
    Attribute attribute2Draft

    def setup() {
        fred = new Contributor(uuid: "user1", userId: "user1", name: "fred")
        jill = new Contributor(uuid: "user2", userId: "user2", name: "jill")
        bob = new Contributor(uuid: "user3", userId: "user3", name: "bob")
        attribute1 = new Attribute(uuid: "attr1", title: new Term(name: "attr1"), text: "attribute1", creators: [fred], editors: [jill, bob])
        attribute2 = new Attribute(uuid: "attr2", title: new Term(name: "attr2"), text: "attribute2", creators: [jill], editors: [fred])
        attribute1Draft = new Attribute(uuid: "attr1", title: new Term(name: "attr3"), text: "attribute3", creators: [fred], editors: [jill, bob])
        attribute2Draft = new Attribute(uuid: "attr2", title: new Term(name: "attr4"), text: "attribute4", creators: [jill], editors: [fred])
        original = new Profile(
                uuid: "uuid",
                scientificName: "sciName",
                nameAuthor: "nameAuthor",
                guid: "guid",
                rank: "rank",
                taxonomyTree: "taxonomyTree",
                nslNameIdentifier: "nslId",
                primaryImage: "primaryImage",
                showLinkedOpusAttributes: true,
                profileStatus: Profile.STATUS_PARTIAL,
                imageSettings: [image1: new ImageSettings(imageDisplayOption: EXCLUDE), image2: new ImageSettings(imageDisplayOption: EXCLUDE)],
                specimenIds: ["spec1", "spec2"],
                authorship: [new Authorship(category: new Term(name: "category1"), text: "bob"), new Authorship(category: new Term(name: "category2"), text: "jill")],
                classification: [new Classification(rank: "kingdom", name: "Plantae"), new Classification(rank: "family", name: "Acacia")],
                links: [new Link(title: "link1"), new Link(title: "link2")],
                bhlLinks: [new Link(title: "bhl1"), new Link(title: "bhl2")],
                bibliography: [new Bibliography(text: "bib1"), new Bibliography(text: "bib2")],
                publications: [new Publication(title: "pub1"), new Publication(title: "pub2")],
                attributes: [attribute1, attribute2],
                attachments: [new Attachment(title: "doc1"), new Attachment(title: "doc2")],
                dateCreated: new Date(),
                isCustomMapConfig: true,
                occurrenceQuery: "q=lsid:http://id.biodiversity.org.au/node/apni/2903532&fq=state:%22New%20South%20Wales%22"
        )

        profileWithDraft = new Profile(
                uuid: "uuid",
                scientificName: "sciName",
                nameAuthor: "nameAuthor",
                guid: "guid",
                rank: "rank",
                taxonomyTree: "taxonomyTree",
                nslNameIdentifier: "nslId",
                primaryImage: "primaryImage",
                showLinkedOpusAttributes: true,
                profileStatus: Profile.STATUS_PARTIAL,
                imageSettings: [image1: new ImageSettings(imageDisplayOption: EXCLUDE), image2: new ImageSettings(imageDisplayOption: EXCLUDE)],
                specimenIds: ["spec1", "spec2"],
                authorship: [new Authorship(category: new Term(name: "category1"), text: "bob"), new Authorship(category: new Term(name: "category2"), text: "jill")],
                classification: [new Classification(rank: "kingdom", name: "Plantae"), new Classification(rank: "family", name: "Acacia")],
                links: [new Link(title: "link1"), new Link(title: "link2")],
                bhlLinks: [new Link(title: "bhl1"), new Link(title: "bhl2")],
                bibliography: [new Bibliography(text: "bib1"), new Bibliography(text: "bib2")],
                publications: [new Publication(title: "pub1"), new Publication(title: "pub2")],
                attributes: [attribute1, attribute2],
                attachments: [new Attachment(title: "doc1"), new Attachment(title: "doc2")],
                dateCreated: new Date(),
                isCustomMapConfig: true,
                occurrenceQuery: "q=lsid:http://id.biodiversity.org.au/node/apni/2903532&fq=state:%22New%20South%20Wales%22",
                draft: new DraftProfile(
                        uuid: "uuid",
                        scientificName: "sciName",
                        nameAuthor: "nameAuthor",
                        guid: "guid",
                        rank: "rank",
                        taxonomyTree: "taxonomyTree2",
                        nslNameIdentifier: "nslId2",
                        primaryImage: "primaryImage2",
                        showLinkedOpusAttributes: false,
                        profileStatus: Profile.STATUS_LEGACY,
                        imageSettings: [image1: new ImageSettings(imageDisplayOption: INCLUDE), image2: new ImageSettings(imageDisplayOption: INCLUDE)],
                        specimenIds: ["spec3", "spec4"],
                        authorship: [new Authorship(category: new Term(name: "category3"), text: "bob2"), new Authorship(category: new Term(name: "category4"), text: "jill2")],
                        classification: [new Classification(rank: "kingdom", name: "Plantae"), new Classification(rank: "family", name: "Acacia")],
                        links: [new Link(title: "link4"), new Link(title: "link3")],
                        bhlLinks: [new Link(title: "bhl4"), new Link(title: "bhl3")],
                        bibliography: [new Bibliography(text: "bib3"), new Bibliography(text: "bib4")],
                        attributes: [attribute1Draft, attribute2Draft],
                        attachments: [new Attachment(title: "doc3"), new Attachment(title: "doc4")],
                        dateCreated: new Date(),
                        isCustomMapConfig: true,
                        occurrenceQuery: "q=lsid:http://id.biodiversity.org.au/node/apni/2903532&fq=state:%22ACT%22"
                )
        )
    }

    def "createDraft should copy all fields"() {
        when:
        DraftProfile draft = CloneAndDraftUtil.createDraft(original)

        then:
        draft.uuid == original.uuid
        draft.scientificName == original.scientificName
        draft.nameAuthor == original.nameAuthor
        draft.rank == original.rank
        draft.guid == original.guid
        draft.nslNameIdentifier == original.nslNameIdentifier
        draft.primaryImage == original.primaryImage
        draft.showLinkedOpusAttributes == original.showLinkedOpusAttributes
        draft.taxonomyTree == original.taxonomyTree
        draft.profileStatus == original.profileStatus

        !draft.imageSettings.is(original.imageSettings)
        draft.imageSettings == original.imageSettings

        !draft.specimenIds.is(original.specimenIds)
        draft.specimenIds == original.specimenIds

        !draft.authorship.is(original.authorship)
        draft.authorship == original.authorship

        !draft.classification.is(original.classification)
        draft.classification == original.classification

        !draft.links.is(original.links)
        draft.links == original.links

        !draft.bhlLinks.is(original.bhlLinks)
        draft.bhlLinks == original.bhlLinks

        !draft.bibliography.is(original.bibliography)
        draft.bibliography == original.bibliography

        !draft.attributes.is(original.attributes)
        draft.attributes == original.attributes as List

        !draft.attachments.is(original.attachments)
        draft.attachments == original.attachments as List

        draft.isCustomMapConfig == original.isCustomMapConfig
        draft.occurrenceQuery == original.occurrenceQuery
    }

    def "updateProfileFromDraftShouldUpdateAllFields"() {
        when:
        CloneAndDraftUtil.updateProfileFromDraft(profileWithDraft)

        then:
        profileWithDraft.uuid == profileWithDraft.draft.uuid
        profileWithDraft.scientificName == profileWithDraft.draft.scientificName
        profileWithDraft.nameAuthor == profileWithDraft.draft.nameAuthor
        profileWithDraft.rank == profileWithDraft.draft.rank
        profileWithDraft.guid == profileWithDraft.draft.guid
        profileWithDraft.nslNameIdentifier == profileWithDraft.draft.nslNameIdentifier
        profileWithDraft.primaryImage == profileWithDraft.draft.primaryImage
        profileWithDraft.showLinkedOpusAttributes == profileWithDraft.draft.showLinkedOpusAttributes
        profileWithDraft.taxonomyTree == profileWithDraft.draft.taxonomyTree
        profileWithDraft.profileStatus == profileWithDraft.draft.profileStatus

        !profileWithDraft.imageSettings.is(profileWithDraft.draft.imageSettings)
        profileWithDraft.imageSettings == profileWithDraft.draft.imageSettings

        !profileWithDraft.specimenIds.is(profileWithDraft.draft.specimenIds)
        profileWithDraft.specimenIds == profileWithDraft.draft.specimenIds

        !profileWithDraft.authorship.is(profileWithDraft.draft.authorship)
        profileWithDraft.authorship == profileWithDraft.draft.authorship

        !profileWithDraft.classification.is(profileWithDraft.draft.classification)
        profileWithDraft.classification == profileWithDraft.draft.classification

        !profileWithDraft.links.is(profileWithDraft.draft.links)
        profileWithDraft.links == profileWithDraft.draft.links

        !profileWithDraft.bhlLinks.is(profileWithDraft.draft.bhlLinks)
        profileWithDraft.bhlLinks == profileWithDraft.draft.bhlLinks

        !profileWithDraft.bibliography.is(profileWithDraft.draft.bibliography)
        profileWithDraft.bibliography == profileWithDraft.draft.bibliography

        !profileWithDraft.attachments.is(profileWithDraft.draft.attachments)
        profileWithDraft.attachments == profileWithDraft.draft.attachments as List

        profileWithDraft.isCustomMapConfig == profileWithDraft.draft.isCustomMapConfig
        profileWithDraft.occurrenceQuery == profileWithDraft.draft.occurrenceQuery

        !profileWithDraft.attributes.is(profileWithDraft.draft.attributes)
        profileWithDraft.attributes == profileWithDraft.draft.attributes as Set
    }

    def "cloneAuthorship should create a new object with copies of all attributes"() {
        given:
        Authorship original = new Authorship(category: new Term(name: "cat1"), text: "text1")

        when:
        Authorship clone = CloneAndDraftUtil.cloneAuthorship(original)

        then:
        !clone.is(original)
        clone == original
    }

    def "clone classification should create a new object with copies of all attributes"() {
        given:
        Classification original = new Classification(name: "name", rank: "rank", guid: "guid")

        when:
        Classification clone = CloneAndDraftUtil.cloneClassification(original)

        then:
        !clone.is(original)
        clone == original
    }

    def "cloneBibliography should create a new object with copies of all attributes"() {
        given:
        Bibliography original = new Bibliography(uuid: "uuid", text: "text", order: 1)

        when:
        Bibliography clone = CloneAndDraftUtil.cloneBibliography(original)

        then:
        !clone.is(original)
        clone == original
    }

    def "cloneLink should create a new object with copies of all attributes"() {
        given:
        Link original = new Link(uuid: "uuid", url: "url", title: "title", description: "description", doi: "doi", edition: "edition", publisherName: "publisher", fullTitle: "fullTitle")

        when:
        Link clone = CloneAndDraftUtil.cloneLink(original)

        then:
        !clone.is(original)
        clone == original
    }

    def "cloneAttachment should create a new object with copies of all attributes"() {
        given:
        Attachment original = new Attachment(uuid: "uuid", title: "title", description: "description", rights: "rights", rightsHolder: "rightsHolder", licence: "licence", creator: "creator", createdDate: new Date())

        when:
        Attachment clone = CloneAndDraftUtil.cloneAttachment(original)

        then:
        !clone.is(original)
        clone == original
    }

    def "cloneAttribute should create a new object with copies of text fields, but the ORIGINAL references"() {
        given:
        Term term = new Term(name: "title")
        Attribute original = new Attribute(uuid: "uuid", text: "text", id: 1L, title: term, original: attribute1, creators: [fred, jill], editors: [bob, jill])

        when:
        Attribute clone = CloneAndDraftUtil.cloneAttribute(original)

        then:
        !clone.is(original)
        clone.uuid == original.uuid
        clone.text == original.text
        clone.id == original.id
        clone.title.is(original.title)
        clone.original.is(original.original)
        !clone.creators.is(original.creators)
        clone.creators[0].is(jill)
        clone.creators[1].is(fred)
        !clone.editors.is(original.editors)
        clone.editors[0].is(bob)
        clone.editors[1].is(jill)
    }
}

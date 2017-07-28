package au.org.ala.profile

import au.org.ala.profile.sanitizer.SanitizedHtml
import groovy.transform.EqualsAndHashCode
import groovy.transform.ToString

@EqualsAndHashCode(includes = ['uuid', 'title', 'text', 'source'])
@ToString(includes = ['uuid', 'title', 'text', 'source'])
class Attribute implements Comparable<Attribute> {

    static auditable = true

    static searchable = {
        root = false
        only = ["text", "title"]
        title component: true
    }

    String uuid
    Term title
    @SanitizedHtml
    String text // = "This animal lives...."
    Attribute original // The original attribute this was copied from
    String source

    Date dateCreated
    Date lastUpdated

    static hasMany = [creators: Contributor, editors: Contributor]

    static belongsTo = [profile: Profile]

    static constraints = {
        text nullable: true
        original nullable: true
        source nullable: true
    }

    static mapping = {
        profile index: true
        uuid index: true
    }

    def beforeValidate() {
        if (uuid == null) {
            //mint an UUID
            uuid = UUID.randomUUID().toString()
        }
        if (text == null) {
            text = ''
        }
    }

    int compareTo(Attribute right) {
        if (title.order == right.title.order) {
            if (title.name.equalsIgnoreCase(right.title.name)) {
                text <=> right.text
            } else {
                title.name.toLowerCase() <=> right.title.name.toLowerCase()
            }
        } else {
            title.order <=> right.title.order
        }
    }
}

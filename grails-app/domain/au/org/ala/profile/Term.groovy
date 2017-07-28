package au.org.ala.profile

import groovy.transform.EqualsAndHashCode
import groovy.transform.ToString

@ToString(includes=['uuid', 'name', 'order', 'required', 'summary', 'containsName'])
@EqualsAndHashCode(includes=['uuid', 'name', 'order', 'required', 'summary', 'containsName'])
class Term implements Comparable<Term> {

    private static final String NOT_ANALYZED_INDEX = "not_analyzed"

    static searchable = {
        only = ["name", "summary", "containsName", "uuid"]
        uuid index: NOT_ANALYZED_INDEX
    }

    String uuid
    String name
    int order = -1
    boolean required = false
    boolean summary = false
    boolean containsName = false

    static belongsTo = [vocab: Vocab]

    static constraints = {}

    def beforeValidate() {
        if (uuid == null) {
            uuid = UUID.randomUUID().toString()
        }
    }

    @Override
    int compareTo(Term other) {
        if (order == other.order) {
            name.toLowerCase() <=> other.name.toLowerCase()
        } else {
            order <=> other.order
        }
    }
}

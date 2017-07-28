package au.org.ala.profile

import spock.lang.Specification

class BaseIntegrationSpec extends Specification {

    def grailsApplication

    /**
     * MongoDB does not support transactions, so any data changed in test will not be rolled back by grails.
     * Therefore, before each test, loop through all domain classes and drop the mongo collection.
     */
    def setup() {
        grailsApplication.domainClasses.each {
            it.clazz.collection.drop()
        }
    }

    public <T> T save(T entity) {
        entity.save(flush: true, failOnError: true)
    }
}

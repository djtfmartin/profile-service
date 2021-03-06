package au.org.ala.profile

import au.org.ala.ws.service.WebService
import grails.test.mixin.TestFor
import org.codehaus.groovy.grails.commons.DefaultGrailsApplication
import spock.lang.Specification

@TestFor(MasterListService)
class MasterListServiceSpec extends Specification {

    def setup() {
        def ga = new DefaultGrailsApplication()
        ga.config.lists.items.cacheSpec = 'maximumSize=0' // disable cache for tests
        service.grailsApplication = ga
        service.init()
    }

    def 'getMasterList always trims names'() {
        given:
        service.webService = Stub(WebService)
        service.webService.get(_) >> [
                status: 200,
                resp: [
                        [name: ' a '],
                        [name: null],
                        [name: 'b'],
                        [name: ' c'],
                        [name: 'd ']
                ]
        ]
        def opus = new Opus(masterListUid: 'test')

        when:
        def results = service.getMasterList(opus)

        then:

        results.each {
            it?.name?.trim() == it?.name
        }

    }
}

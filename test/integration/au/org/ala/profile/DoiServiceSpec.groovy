package au.org.ala.profile

import au.org.ala.web.AuthService
import au.org.ala.web.UserDetails
import au.org.ala.ws.service.WebService
import groovyx.net.http.HttpResponseDecorator
import groovyx.net.http.RESTClient
import org.apache.http.HttpVersion
import org.apache.http.message.BasicHttpResponse
import org.apache.http.message.BasicStatusLine

class DoiServiceSpec extends BaseIntegrationSpec {

    DoiService service = new DoiService()

    def setup() {
        service.grailsApplication = [config:
                                             [ doi : [
                                                      service: [url: "http://ands.bla.bla/"],
                                                      resolution: [
                                                              url: [prefix: "http://blabla/publication"]
                                                      ]
                                              ],
                                               profile : [hub: [base: [url: "https://prod.blah/"]]]
                                             ]
        ]
    }

    def mockServiceResponse(int statusResponseCode, Map statusJson, int mintResponseCode, Map mintJson) {
        RESTClient.metaClass.get { Map<String, ?> args ->
            BasicHttpResponse baseResponse
            HttpResponseDecorator decorator = null
            if (delegate.getUri().toString().endsWith("status.json")) {
                baseResponse = new BasicHttpResponse(new BasicStatusLine(HttpVersion.HTTP_1_1, statusResponseCode, "bla"))
                decorator = new HttpResponseDecorator(baseResponse, statusJson)
            } else {
                println "Unexpected service call"
            }

            decorator
        }

        RESTClient.metaClass.post { Map<String, ?> args ->
            BasicHttpResponse baseResponse
            HttpResponseDecorator decorator = null
            if (delegate.getUri().toString().endsWith("mint.json/")) {
                baseResponse = new BasicHttpResponse(new BasicStatusLine(HttpVersion.HTTP_1_1, mintResponseCode, "bla"))
                decorator = new HttpResponseDecorator(baseResponse, mintJson)
            } else {
                println "Unexpected service call"
            }

            decorator
        }
    }

    def "mintDoi should return an error if the HTTP status of the DOI service status check is not 200"() {
        setup:
        service.webService = Mock(WebService)
        service.webService.post(_, _, _, _, _, _, _) >> [statusCode: org.springframework.http.HttpStatus.BAD_REQUEST.value(), error: org.springframework.http.HttpStatus.BAD_REQUEST.reasonPhrase ]
        service.authService = Mock(AuthService)
        service.authService.getUserId() >> "user1"
        service.authService.getUserForUserId(_) >> new UserDetails(userId: 'user1', userName: "username1")

        when:

        Map result = service.mintDOI(new Opus(title: "Opus"), new Publication(authors: "fred", title: "species1", publicationDate: new Date(), version: 1))

        then:
        result.status == "error"
    }

    def "mintDoi should return an error if the HTTP status of the DOI mint service is not 200"() {
        setup:
        service.webService = Mock(WebService)
        service.webService.post(_, _, _, _, _, _, _) >> [statusCode: org.springframework.http.HttpStatus.OK.value(), resp: [ status: "error", error: org.springframework.http.HttpStatus.INTERNAL_SERVER_ERROR.reasonPhrase] ]
        service.authService = Mock(AuthService)
        service.authService.getUserId() >> "user1"
        service.authService.getUserForUserId(_) >> new UserDetails(userId: 'user1', userName: "username1")

        when:

        Map result = service.mintDOI(new Opus(title: "Opus"), new Publication(authors: "fred", title: "species1", publicationDate: new Date(), version: 1))

        then:
        result.status == "error"
    }

    def "mintDoi should return an error if the DOI mint service returns a response code other than the 'success' code"() {
        setup:
        service.webService = Mock(WebService)
        service.webService.post(_, _, _, _, _, _, _) >> [statusCode: org.springframework.http.HttpStatus.OK.value(), resp: [ status: "xyz", error: org.springframework.http.HttpStatus.INTERNAL_SERVER_ERROR.reasonPhrase] ]
        service.authService = Mock(AuthService)
        service.authService.getUserId() >> "user1"
        service.authService.getUserForUserId(_) >> new UserDetails(userId: 'user1', userName: "username1")

        when:

        Map result = service.mintDOI(new Opus(title: "Opus"), new Publication(authors: "fred", title: "species1", publicationDate: new Date(), version: 1))

        then:
        result.status == "error"
    }

    def "mintDoi should return a the DOI if the DOI mint service returns the 'success' response code"() {
        setup:
        service.webService = Mock(WebService)
        service.webService.post(_, _, _, _, _, _, _) >> [statusCode: org.springframework.http.HttpStatus.OK.value(), resp: [ status: "ok", doi: "12345"] ]
        service.authService = Mock(AuthService)
        service.authService.getUserId() >> "user1"
        service.authService.getUserForUserId(_) >> new UserDetails(userId: 'user1', userName: "username1")

        when:

        Map result = service.mintDOI(new Opus(title: "Opus"), new Publication(authors: "fred", title: "species1", publicationDate: new Date(), version: 1))

        then:
        result.status == "success"
        result.doi == "12345"
    }

    def "mintDoi should return a the DOI if the DOI mint service returns the 'error' response code but created doi"() {
        setup:
        service.webService = Mock(WebService)
        service.webService.post(_, _, _, _, _, _, _) >> [statusCode: org.springframework.http.HttpStatus.OK.value(), resp: [ status: "error", doi: "12345"] ]
        service.authService = Mock(AuthService)
        service.authService.getUserId() >> "user1"
        service.authService.getUserForUserId(_) >> new UserDetails(userId: 'user1', userName: "username1")

        when:

        Map result = service.mintDOI(new Opus(title: "Opus"), new Publication(authors: "fred", title: "species1", publicationDate: new Date(), version: 1))

        then:
        result.status == "success"
        result.doi == "12345"
    }

    def "buildJSONForDataCite should return correct payload for "() {
        setup:
        service.webService = Mock(WebService)
        service.webService.post(_, _, _, _, _, _, _) >> [statusCode: org.springframework.http.HttpStatus.OK.value(), resp: [ status: "error", doi: "12345"] ]
        service.authService = Mock(AuthService)
        service.authService.getUserId() >> "user1"
        service.authService.getUserForUserId(_) >> new UserDetails(userId: 'user1', userName: "username1")

        when:
        Map result = service.buildJSONForDataCite(new Opus(title: "Opus", uuid: 'opus1'), new Publication(authors: "fred", title: "species1", publicationDate: new Date(), version: 1), new Profile(uuid: 'profile1'))

        then:
        result.applicationUrl == "https://prod.blah/opus/opus1/profile/profile1"

        when:
        result = service.buildJSONForDataCite(new Opus(title: "Opus", uuid: 'opus1'), new Publication(authors: "fred", title: "species1", publicationDate: new Date(), version: 1), null)

        then:
        result.applicationUrl == "https://prod.blah/"
    }

}

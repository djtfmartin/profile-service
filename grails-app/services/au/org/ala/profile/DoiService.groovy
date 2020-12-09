package au.org.ala.profile

import au.org.ala.web.AuthService
import au.org.ala.ws.service.WebService
import org.springframework.http.HttpStatus

import java.text.SimpleDateFormat

class DoiService {

    static final String PROVIDER_DATACITE = "DATACITE"

    AuthService authService
    WebService webService
    def grailsApplication

    /**
     * Mint DOI user doi-service API. Doi-service uses DataCite to generate DOI.
     * @param opus
     * @param publication
     * @return
     */
    Map mintDOI(Opus opus, Publication publication, Profile profile = null) {
        Map result = [:]

        log.debug "Requesting new DOI from doi-service..."
        String doiURL = "${grailsApplication.config.doi.service.url}api/doi"
        Map requestJSON = buildJSONForDataCite(opus, publication, profile)
        log.debug requestJSON

        Map headers = ["Content-Type": org.apache.http.entity.ContentType.APPLICATION_JSON, "Accept-Version": "1.0"]
        Map response = webService.post(doiURL, requestJSON, [:], org.apache.http.entity.ContentType.APPLICATION_JSON, true, false, headers )

        if(response.resp) {
            def json = response.resp

            // check if doi generated successfully. Or,
            // check doi-service successfully created doi but failed on saving to database.
            if ((json?.status == "ok") || ((json?.status == "error") && json?.doi)) {
                log.debug "DOI response = ${json}"

                log.debug "Minted new doi ${json.doi}"
                result.status = "success"
                result.doi = json.doi
            } else {
                result.status = "error"
                result.errorCode = HttpStatus.INTERNAL_SERVER_ERROR
                result.error = "${json.error}"

                log.error("Failed to mint new doi: ${json.error}")
            }
        } else {
            result.status = "error"
            result.errorCode = response.statusCode
            def status
            try {
                status = HttpStatus.valueOf(response.statusCode)
            } catch (IllegalArgumentException e) {
                status = HttpStatus.BAD_REQUEST
            }

            result.error = status.reasonPhrase
        }

        return result
    }

    /**
     * Build a schema in a format accepted by doi-service for DATACITE.
     * @param opus
     * @param publication
     * @return
     */
    Map buildJSONForDataCite(Opus opus, Publication publication, Profile profile = null) {
        String applicationUrl = profile ? "${grailsApplication.config.profile.hub.base.url}/opus/${opus.uuid}/profile/${profile.uuid}" : grailsApplication.config.profile.hub.base.url

        [
                "provider"            : PROVIDER_DATACITE,
                "title"               : "${publication.title}",
                "authors"             : publication.authors,
                "description"         : "Taxonomic treatment for ${publication.title}",
                "applicationUrl"      : applicationUrl,
                "customLandingPageUrl": "${grailsApplication.config.doi.resolution.url.prefix}",
                "userId"              : "${authService.getUserId()}",
                "providerMetadata"    : [
                        "resourceType"   : "Text",
                        "resourceText"   : "Species information",
                        "creators"       : [["name": "${publication.authors}"]],
                        "title"          : "${publication.title}",
                        "subtitle"       : "Version ${publication.version}",
                        "publisher"      : "${opus.title}",
                        "publicationYear": Calendar.getInstance().get(Calendar.YEAR),
                        "subjects"       : [publication.title],
                        "contributors"   : [[type: "Editor", name: "${authService.getUserForUserId(authService.getUserId())?.displayName}"]],
                        "createdDate"    : new SimpleDateFormat("yyyy-MM-dd").format(publication.publicationDate),
                        "descriptions"   : [[type: "Other", text: "Taxonomic treatment for ${publication.title}"]]
                ]
        ]
    }
}

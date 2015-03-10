class UrlMappings {

	static mappings = {


        "/audit/object/$uuid"(controller: "audit", action: [GET:"auditTrailForObject"])

        "/audit/user/$userId"(controller: "audit", action: [GET:"auditTrailForUser"])
        
        "/classification"(controller: "profile", action: [GET:"classification"])

        "/vocab/"(controller: "vocab", action: "index")

        "/vocab/$vocabId"(controller: "vocab", action: "show")

        "/attribute/"(controller: "attribute", action: [GET:"index", PUT:"create",  POST:"create"])

        "/attribute/$attributeId"(controller: "attribute", action: [GET:"show", PUT:"update", DELETE:"delete", POST:"update"])

        "/opus/"(controller: "opus", action: [GET: "index", PUT: "create", POST: "create"])

        "/opus/$opusId"(controller: "opus", action: [GET: "show", POST:"updateOpus", DELETE: "deleteOpus"])

        "/opus/taxaUpload"(controller: "opus", action: "taxaUpload")

        "/profile/search"(controller: "profile", action: "search")

        "/profile/$profileId"(controller: "profile", action: "getByUuid")

        "/profile/"(controller: "profile", action: "index")

        "/profile/links/$profileId"(controller: "profile", action: [POST:"saveLinks"])

        "/profile/bhl/$profileId"(controller: "profile", action: [POST:"saveBHLLinks"])

        "/$controller/$action?/$id?(.$format)?"{
            constraints {
                // apply constraints here
            }
        }

        "/"(view:"/index")
        "500"(view:'/error')

        // TODO remove these importXYZ and test mappings
        "/importFOA"(controller: "profile", action: "importFOA")
        "/importSponges"(controller: "profile", action: "importSponges")
        "/createTestOccurrenceSource"(controller: 'profile', action: 'createTestOccurrenceSource')

	}
}

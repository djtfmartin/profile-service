package au.org.ala.profile

class FlushAuditMessageQueueJob {

    def auditService

    static triggers = {
        simple repeatInterval: 5000l // execute job once in 5 seconds
    }

    def execute() {
        auditService.flushMessageQueue()
    }
}

package io.nook.web

import io.nook.contract.ErrorCode
import io.nook.contract.Invocation
import io.nook.contract.Missing
import io.nook.contract.RecordingCore
import io.nook.contract.StructuredError
import io.nook.contract.StructuredErrorException
import java.util.concurrent.CountDownLatch
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds

/**
 * The shared stand-in, made to misbehave when a call names one of the references
 * below.
 *
 * The misbehaviours ride on the reference a call names rather than on a switch,
 * so one request written once can be sent to both addresses and produce the same
 * ending at each without anything being set up in between.
 */
class StandInCore : RecordingCore() {

    /** How long a call naming [SLOW] sits before it answers. */
    var patience: Duration = 100.milliseconds

    override fun before(invocation: Invocation) {
        (listOfNotNull(invocation.project) + invocation.values.filterIsInstance<String>()).forEach(::actOn)
    }

    /** What a call naming [reference] gets instead of an answer, where the reference asks for one. */
    private fun actOn(reference: String) {
        when (reference) {
            NOT_THERE -> refuse(ErrorCode.NOT_FOUND, "nothing answers to \"$NOT_THERE\"", Missing.ITEM.asDetails())
            TAKEN -> refuse(ErrorCode.CONFLICT, "\"$TAKEN\" is already held by something else")
            A_LOOP -> refuse(ErrorCode.CYCLE, "\"$A_LOOP\" would wait on itself")
            NOT_ALLOWED -> refuse(ErrorCode.VALIDATION_FAILED, "\"$NOT_ALLOWED\" is not a thing to ask for")
            GONE -> refuse(ErrorCode.NOT_FOUND, "no project answers to \"$GONE\"", Missing.PROJECT.asDetails())
            A_DEFECT -> error("a defect planted inside the core")
            SLOW -> Thread.sleep(patience.inWholeMilliseconds)
            SILENT -> CountDownLatch(1).await()
        }
    }

    private fun refuse(code: ErrorCode, said: String, details: Map<String, String>? = null): Nothing =
        throw StructuredErrorException(StructuredError(code, said, details))
}

/** The references a call names to be refused, broken on, kept waiting, or never answered. */
const val NOT_THERE: String = "not-there"

const val TAKEN: String = "taken"

const val A_LOOP: String = "a-loop"

const val NOT_ALLOWED: String = "not-allowed"

const val GONE: String = "gone"

const val A_DEFECT: String = "a-defect"

const val SLOW: String = "slow"

const val SILENT: String = "silent"

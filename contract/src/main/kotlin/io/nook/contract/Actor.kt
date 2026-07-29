package io.nook.contract

/**
 * Who a call is for, and what made it.
 *
 * Two identities rather than one, because on the agent surface two parties are
 * involved in every write: a coding agent does the work, and the person it is
 * working for is the one whose project it is. Recording only the agent loses
 * the person; recording only the person loses which agent to ask about a
 * change. OAuth's token exchange standard
 * ([RFC 8693](https://www.rfc-editor.org/rfc/rfc8693)) splits exactly that pair
 * — the party acted for and the party acting — and this follows that split
 * rather than inventing one.
 *
 * [subject] is the person, taken from the `sub` claim of the token a call
 * presented and from nothing a caller can write. [agent] is the coding agent,
 * by the name its own client announced when it opened its connection; it is
 * nothing wherever a person acted directly, which is every call on the web API
 * and any connection whose client named itself with nothing.
 *
 * Both may be absent, which is how a call reaching the core naming nobody
 * arrives — the write path refuses one, and a read is served.
 */
public data class Actor(public val subject: String? = null, public val agent: String? = null) {

    public companion object {
        /** A call naming neither: what an unbound caller sends, and what a defect at an adapter would. */
        public val NOBODY: Actor = Actor()
    }
}

/**
 * The two names an adapter tells the core beside a request, rather than inside it.
 *
 * They are Nook's own, and that is a deliberate exception to taking the names
 * the world already wrote. No published specification registers a header for
 * an adapter telling a service behind it whose identity it has already checked:
 * the standards here all describe a *caller* presenting a credential, and the
 * one that would apply — handing the caller's own token onward — is what the
 * protocol's security rules forbid outright. So the meaning is RFC 8693's and
 * only the two names are Nook's, and they are read nowhere but on the
 * connection between an adapter and the core.
 */
public const val SUBJECT_HEADER: String = "Nook-Subject"

public const val AGENT_HEADER: String = "Nook-Agent"

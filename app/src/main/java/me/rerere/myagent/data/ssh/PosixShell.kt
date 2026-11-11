package me.rerere.myagent.data.ssh

/**
 * POSIX single-quote escaping: wrap [value] in single quotes and rewrite each embedded quote as
 * `'\''` (close quote, escaped literal quote, reopen quote).
 *
 * Every remote command we build goes through this: sshd hands the exec string to the login
 * shell, so a path or argument containing a space, `$`, `;` or a quote would otherwise be
 * reinterpreted on the far side. Single quotes are the only quoting form that leaves everything
 * else literal.
 *
 * Lives in `data.ssh` because both the SSH tools and the LSP/DAP servers need it; a second copy
 * would be a second place for the quoting rule to drift.
 */
internal fun posixShellQuote(value: String): String = "'" + value.replace("'", "'\\''") + "'"

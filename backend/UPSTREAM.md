# Backend provenance

The EarCEO backend core was derived from
[`onezion12344/coding-vibe`](https://github.com/onezion12344/coding-vibe) at
commit `03cdf08`.

The task lifecycle and state-store reliability work was first developed in a
separate local checkout as commit `95225d1`, then imported into the EarCEO
monorepo together with the Android-facing Gateway. EarCEO's Git history from
that integration point onward is authoritative for this code.

The upstream README describes Coding Vibe as MIT-licensed, but the imported
snapshot did not contain a tracked license file. Verify upstream licensing and
add the appropriate license notice before distributing this backend outside
the EarCEO project.

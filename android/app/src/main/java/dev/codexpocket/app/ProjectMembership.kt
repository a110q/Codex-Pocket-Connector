package dev.codexpocket.app

import org.json.JSONObject

private fun emptyHost(value:JSONObject)=value.isNull("hostId")||value.optString("hostId").isBlank()

// Only an explicitly local project can repair an empty host field. Preserve
// nonempty hosts and remote project kinds so they cannot route to this PC.
internal fun normalizeLocalProjectHost(project:JSONObject):JSONObject =
    if(project.optString("projectKind")=="local"&&emptyHost(project))JSONObject(project.toString()).put("hostId","local")else project

internal fun normalizeLocalThreadHost(thread:JSONObject):JSONObject =
    if(thread.optString("kind","codex")=="codex"&&thread.optString("projectKind","local")=="local"&&emptyHost(thread))
        JSONObject(thread.toString()).put("hostId","local")else thread

internal fun localProjects(values:List<JSONObject>)=values.map(::normalizeLocalProjectHost).filter{
    it.optString("projectKind","local")=="local"&&it.optString("hostId","local")=="local"
}

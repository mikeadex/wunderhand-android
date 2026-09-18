package com.wunderhand.app.app

import com.wunderhand.core.DraftProblem
import com.wunderhand.network.ApiError

/** Trouble that is the whole app's — signed out, off the team, too old a build — not one screen's to word. */
fun ApiError.isTheApps() = this is ApiError.Unauthorized || this is ApiError.NotMember || this is ApiError.UpgradeRequired

/** What could not be saved, and the field it was about. */
data class SaveProblem(val text: String, val field: String? = null) {
    constructor(error: ApiError) : this(error.message, (error as? ApiError.Validation)?.field)
    constructor(error: DraftProblem) : this(error.message, error.field)
}

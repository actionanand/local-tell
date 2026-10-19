package com.actionanand.localtell.app.easy

internal object EasyFindInput {
    fun canSubmit(input: String, locating: Boolean): Boolean = input.isNotBlank() && !locating
}

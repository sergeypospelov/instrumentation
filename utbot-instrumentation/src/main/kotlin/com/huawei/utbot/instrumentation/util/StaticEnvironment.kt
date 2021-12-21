package com.huawei.utbot.instrumentation.util

import com.huawei.utbot.framework.plugin.api.FieldId

typealias StaticField = Pair<FieldId, Any?>

/**
 * Container for static fields.
 */
class StaticEnvironment() {
    private val listOfFields_ = mutableListOf<StaticField>()
    val listOfFields: List<StaticField>
        get() = listOfFields_

    constructor(fields: List<StaticField>) : this() {
        addStaticFields(fields)
    }

    constructor(vararg fields: StaticField) : this() {
        addStaticFields(fields.asList())
    }

    fun addStaticFields(fields: List<StaticField>) {
        listOfFields_.addAll(fields)
    }
}
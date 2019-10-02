package ru.tcsbank.sme.reporting

data class TemplateMetadata (
    val rowNum: String,
    val workCode: String,
    val okud: String,
    val period: String,
    val shortName: String,
    val name: String,
    val createDate: String,
    val economicalXmlLink: String,
    val templateXmlLink: String,
    val updateDate: String,
    var code: String?,
    var idp: String?,
    var parsedName: String?,
    var parsedOkud: String?
)
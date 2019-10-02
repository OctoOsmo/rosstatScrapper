package ru.tcsbank.sme.reporting

data class TemplateMetadata (
    var rowNum: String,
    var workCode: String,
    var okud: String,
    var period: String,
    var shortName: String,
    var name: String,
    var createDate: String,
    var economicalXmlLink: String,
    var templateXmlLink: String,
    var updateDate: String,
    var code: String?,
    var idp: String?,
    var parsedName: String?,
    var parsedOkud: String?
)
package ru.tcsbank.sme.reporting

import com.github.junrar.Junrar
import org.apache.commons.io.FileUtils
import org.jsoup.Jsoup
import org.jsoup.nodes.Node
import org.w3c.dom.Document
import java.io.File
import java.net.URL
import java.net.URLEncoder
import javax.xml.parsers.DocumentBuilderFactory

class App {
    val greeting: String
        get() {
            return "Hello world."
        }
}

const val INSERT_START = "insert into sa_rosstat_report_name(okud, code, idp, name, short_name) values ("
const val INSERT_END = ");"

fun main() {
    val tableNode = Jsoup.parse(
        URL("https://www.gks.ru/metod/XML-2019/XML_plan_2019.htm"), 20000
    ).body().childNodes()[13].childNodes()[1]

    val content = tableNode.childNodes()[2].childNodes()
        .filterIndexed { index, _ -> index > 4 }
        .filterIndexed { index, _ -> isUneven(index) }
        .filterIndexed { index, _ -> index < 204 }

    val modelList = content.map { row -> fillMetadata(row) }
    modelList.forEachIndexed { index, templateMetadata ->
        if ("\\d\\d.\\d\\d.\\d\\d\\d\\d".toRegex().matches(templateMetadata.rowNum)) {
            templateMetadata.createDate = templateMetadata.rowNum
            val link = extractLinkFromRow(content[index], 2)

            if (link.isNotEmpty() && link != "https://www.gks.ru/metod/XML-2019/") {
                templateMetadata.templateXmlLink = link
                fillFromPrevious(templateMetadata, modelList, index)
            }
        } else if (templateMetadata.rowNum == "квартальная" || templateMetadata.rowNum == "годовая") {
            when {
                templateMetadata.okud == "18.01.2019 начиная с отчета за январь-март 2019 года" -> {
                    val link = extractLinkFromRow(content[index], 1)

                    if (link.isNotEmpty() && link != "https://www.gks.ru/metod/XML-2019/") {
                        templateMetadata.templateXmlLink = link
                        fillFromPrevious(templateMetadata, modelList, index)
                    }
                }
                templateMetadata.okud == "27.12.2018 Размещение для ТОГС" -> {
                    val link = extractLinkFromRow(content[index], 1)

                    if (link.isNotEmpty() && link != "https://www.gks.ru/metod/XML-2019/") {
                        templateMetadata.templateXmlLink = link
                        fillFromPrevious(templateMetadata, modelList, index)
                    }
                }
                else -> {
                    templateMetadata.period = templateMetadata.rowNum
                    templateMetadata.createDate = templateMetadata.workCode

                    val link = extractLinkFromRow(content[index], 3)

                    if (link.isNotEmpty() && link != "https://www.gks.ru/metod/XML-2019/") {
                        templateMetadata.templateXmlLink = link
                        fillFromPrevious(templateMetadata, modelList, index)
                    }
                }
            }

        } else if (templateMetadata.rowNum == "Скачать"
            && templateMetadata.workCode == "Скачать"
        ) {
            val link = extractLinkFromRow(content[index], 1)

            if (link.isNotEmpty() && link != "https://www.gks.ru/metod/XML-2019/") {
                templateMetadata.templateXmlLink = link
                fillFromPrevious(templateMetadata, modelList, index)
            }
        } else if (templateMetadata.rowNum == "Скачать"
            && templateMetadata.workCode == "08.08.2019 для отчёта за год На основании приказа Росстата от 19.06.2019г.№344"
        ) {
            val link = extractLinkFromRow(content[index], 0)

            if (link.isNotEmpty() && link != "https://www.gks.ru/metod/XML-2019/") {
                templateMetadata.templateXmlLink = link
                fillFromPrevious(templateMetadata, modelList, index)
            }
        } else if (templateMetadata.rowNum == "0604010") {
            val link = extractLinkFromRow(content[index], 6)

            templateMetadata.templateXmlLink = link

            templateMetadata.okud = templateMetadata.rowNum
            templateMetadata.period = templateMetadata.workCode
            templateMetadata.shortName = templateMetadata.okud
            templateMetadata.name = templateMetadata.period

            templateMetadata.rowNum = "157"
            templateMetadata.workCode = "15014046"
        }
    }
    val (validValues, invalidValues) =
        modelList.partition {
            it.okud.length == 7 &&
                    it.rowNum.length < 4
        }

    val filledValidValues = validValues.map { templateMetadata ->
        extractAndFillMetadata(templateMetadata)
    }
        .flatten()
        .filter { it.parsedOkud?.length == 7 }

    val sqlQueries = filledValidValues.map {
        "$INSERT_START'${it.parsedOkud}', '${it.code}', '${it.idp}', '${it.name}', '${it.shortName}'$INSERT_END"
    }.toSet()

    val printWriter = File("reportName.sql").printWriter()
    sqlQueries.forEach { printWriter.println(it) }
    printWriter.close()

    if (invalidValues.isEmpty()) println("Invalid values") else println("No invalid values")
    invalidValues.forEach {
        if (it.rowNum != "19.12.2019")//empty line
            println(it)
    }

    println(App().greeting)
}

private fun extractAndFillMetadata(templateMetadata: TemplateMetadata): List<TemplateMetadata> {
    println("extracting ${templateMetadata.okud}")

    File("archives").mkdir()
    File("extracted").mkdir()
    val index = templateMetadata.templateXmlLink.lastIndexOf("/")
    val filename = templateMetadata.templateXmlLink.substring(index + 1)

    val file = File("archives/${templateMetadata.okud}_${filename}.rar")
    val link = templateMetadata.templateXmlLink.substring(0, index + 1)
    val url =
        URL(link + if (filename == "18-КС_.rar" || filename == "st_18_с1_2.rar") URLEncoder.encode(filename) else filename)
    FileUtils.copyURLToFile(url, file, 1000, 10000)

    val extractDir = File("extracted/${templateMetadata.okud}_${filename}").also { it.mkdir() }
    Junrar.extract(file, extractDir)

//    val xmlFilesCount = extractDir.listFiles()?.filter { it.name.endsWith(".xml") }?.size ?: 0
//    if (xmlFilesCount > 1)
//        println("WARNING ${templateMetadata.okud} have $xmlFilesCount xml files")

    return extractDir.listFiles()?.filter { it.name.endsWith(".xml") }
        ?.map {
            fillTemplateMetadataFromTemplate(it, templateMetadata)
        }?.toList()
        ?: throw IllegalStateException("template not found")
}

private fun fillTemplateMetadataFromTemplate(
    xmlTemplateFile: File,
    templateMetadata: TemplateMetadata
): TemplateMetadata {
    val xmlTemplate = parse(xmlTemplateFile)
    val attributes = xmlTemplate.documentElement.attributes
    attributes.length
    val map = setOf("code", "idp", "name", "OKUD").map { it to attributes.getNamedItem(it) }.toMap()

    val filledMetadata = templateMetadata.copy()
    filledMetadata.code = map["code"]?.nodeValue ?: throw IllegalStateException("value not found")
    filledMetadata.idp = map["idp"]?.nodeValue ?: throw IllegalStateException("value not found")
    filledMetadata.parsedName = map["name"]?.nodeValue ?: throw IllegalStateException("value not found")
    filledMetadata.parsedOkud = map["OKUD"]?.nodeValue ?: throw IllegalStateException("value not found")
    return filledMetadata
}

private fun fillFromPrevious(
    templateMetadata: TemplateMetadata,
    modelList: List<TemplateMetadata>,
    index: Int
) {
    templateMetadata.rowNum = (modelList[index - 1].rowNum.toInt() + 1).toString()
    templateMetadata.workCode = modelList[index - 1].workCode
    templateMetadata.okud = modelList[index - 1].okud
    templateMetadata.name = modelList[index - 1].name
    templateMetadata.shortName = modelList[index - 1].shortName
}

fun parse(file: File): Document {
    val factory = DocumentBuilderFactory.newInstance()
    val builder = factory.newDocumentBuilder()
    return builder.parse(file)
}

fun fillMetadata(row: Node): TemplateMetadata {
    val rows = row.childNodes().filterIndexed { index, node -> isUneven(index) }
    return TemplateMetadata(
        rowNum = extractVal(0, rows),
        workCode = extractVal(1, rows),
        okud = extractVal(2, rows),
        period = extractVal(3, rows),
        shortName = extractVal(4, rows),
        name = extractVal(5, rows),
        createDate = extractVal(6, rows),
        economicalXmlLink = extractLink(7, rows),
        templateXmlLink = extractLink(8, rows),
        updateDate = extractVal(9, rows),
        code = null,
        idp = null,
        parsedName = null,
        parsedOkud = null
    )
}

private fun extractLinkFromRow(row: Node, index: Int): String {
    val rows = row.childNodes().filterIndexed { ind, _ -> isUneven(ind) }
    return extractLink(index, rows)
}

private fun isUneven(index: Int) = index % 2 == 1

private fun extractLink(index: Int, nodes: List<Node>) =
    try {
        "https://www.gks.ru/metod/XML-2019/${nodes[index].childNodes()[0].attributes()["href"]}"
    } catch (e: Exception) {
        ""
    }

private fun extractVal(index: Int, nodes: List<Node>) =
    try {
        (nodes[index] as org.jsoup.nodes.Element).text()
    } catch (e: Exception) {
        ""
    }

data class TemplateMetadata(
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
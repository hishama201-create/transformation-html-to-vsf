package com.dev.backupapp

import java.io.File
import java.io.FileWriter

data class Contact(val name: String, val phone: String)

class HtmlToVcfConverter {

    fun convertHtmlToVcf(htmlContent: String, outputFile: File): Boolean {
        return try {
            val contacts = extractContactsFromHtml(htmlContent)
            writeVcfFile(contacts, outputFile)
            true
        } catch (e: Exception) {
            e.printStackTrace()
            false
        }
    }

    private fun extractContactsFromHtml(html: String): List<Contact> {
        val contacts = mutableListOf<Contact>()
        val phoneRegex = Regex("""(\+?\d[\d\s\-]{8,}\d)""")
        val nameRegex = Regex("""(?:name|اسم)[\s:=]+([^\n<]+)""", RegexOption.IGNORE_CASE)

        val lines = html.split("\n")
        var currentName = "Contact"

        for (line in lines) {
            val nameMatch = nameRegex.find(line)
            if (nameMatch != null) {
                currentName = nameMatch.groupValues[1].trim()
            }
            val phoneMatch = phoneRegex.find(line)
            if (phoneMatch != null) {
                val phone = phoneMatch.value.replace(Regex("""\s+"""), "")
                contacts.add(Contact(currentName, phone))
                currentName = "Contact"
            }
        }
        return contacts
    }

    private fun writeVcfFile(contacts: List<Contact>, outputFile: File) {
        FileWriter(outputFile).use { writer ->
            for (contact in contacts) {
                writer.write("BEGIN:VCARD\n")
                writer.write("VERSION:3.0\n")
                writer.write("FN:${contact.name}\n")
                writer.write("TEL;TYPE=CELL:${contact.phone}\n")
                writer.write("END:VCARD\n\n")
            }
        }
    }
}

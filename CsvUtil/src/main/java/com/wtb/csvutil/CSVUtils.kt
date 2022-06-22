package com.wtb.csvutil

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.OpenableColumns
import android.widget.Toast
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import com.github.doyaaaaaken.kotlincsv.dsl.csvReader
import com.github.doyaaaaaken.kotlincsv.dsl.csvWriter
import com.wtb.csvutil.CSVUtils.Companion.dtf
import java.io.*
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import kotlin.reflect.KClass
import kotlin.reflect.KProperty1

class CSVUtils(private val ctx: AppCompatActivity) {
    fun export(vararg itemLists: CSVConvertiblePackExport<*>) {
        fun generateFile(context: Context, fileName: String): File? {
            val csvFile = File(context.filesDir, fileName)
            csvFile.createNewFile()

            return if (csvFile.exists()) {
                csvFile
            } else {
                null
            }
        }

        fun zipFiles(context: Context, vararg files: File): File {
            val outFile = File(context.filesDir, getZipFileName())
            val zipOut = ZipOutputStream(BufferedOutputStream(outFile.outputStream()))

            files.forEach { file ->
                val fi = FileInputStream(file)
                val origin = BufferedInputStream(fi)
                val entry = ZipEntry(file.name)
                zipOut.putNextEntry(entry)
                origin.copyTo(zipOut, 1024)
                origin.close()
            }
            zipOut.close()

            return outFile
        }

        fun getSendFilesIntent(file: File): Intent {
            val intent = Intent(Intent.ACTION_SEND)
            val contentUri = FileProvider.getUriForFile(
                ctx, "${ctx.packageName}.fileprovider", file
            )
            intent.data = contentUri
            intent.putExtra(Intent.EXTRA_STREAM, contentUri)
            intent.flags =
                Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION

            return intent
        }

        fun <T : Any> exportData(
            csvFile: File,
            convert: CSVConvertiblePackExport<T>
        ) {
            csvWriter().open(csvFile, append = false) {
                writeRow(convert.headerList)
                convert.items.ifEmpty { null }?.forEach {
                    writeRow(convert.asList(it))
                }
            }
        }

        val csvFiles = mutableListOf<File>()

        itemLists.forEach {
            val outFile: File? = generateFile(ctx, it.fileName)

            if (outFile != null) {
                exportData(outFile, it)
                csvFiles.add(outFile)
            } else {
                ctx.runOnUiThread {
                    Toast.makeText(
                        ctx,
                        "Error creating file ${it.fileName}",
                        Toast.LENGTH_LONG
                    ).show()
                }
            }
        }

        val zipFile: File = zipFiles(ctx, *csvFiles.toTypedArray())

        csvFiles.forEach { it.delete() }

        ctx.runOnUiThread {
            ContextCompat.startActivity(
                ctx,
                Intent.createChooser(getSendFilesIntent(zipFile), null),
                null
            )
        }
    }

    fun import(activityResultLauncher: ActivityResultLauncher<String>) {
        ctx.runOnUiThread {
            activityResultLauncher.launch("application/zip")
        }
    }

    private fun extractZip(
        uri: Uri,
        kList: List<CSVConvertiblePackImport<*, *>>,
        action: (ModelMap) -> Unit,
        exceptionHandler: ((Exception) -> Unit)?
    ) {
        fun extractToFileInputStream(nextEntry: ZipEntry, zipIn: ZipInputStream): FileInputStream {
            val res = File(ctx.filesDir, nextEntry.name)
            if (!res.canonicalPath.startsWith(ctx.filesDir.canonicalPath)) {
                throw SecurityException()
            }
            FileOutputStream(res).use { t ->
                zipIn.copyTo(t, 1024)
            }
            return FileInputStream(res)
        }

        ZipInputStream(ctx.contentResolver.openInputStream(uri)).use { zipIn ->
            var nextEntry: ZipEntry? = zipIn.nextEntry
            val imports = ModelMap()
            val headerMap = mutableMapOf<Set<String>, KClass<out CSVConvertible<*>>>().apply {
                kList.forEach { ccpi ->
                    this[ccpi.headers] = ccpi.kClass
                }
            }

            while (nextEntry != null) {
                val inputStream = extractToFileInputStream(nextEntry, zipIn)
                val csvIn: List<Map<String, String>> = csvReader().readAllWithHeader(inputStream)
                val headers: Set<String> = csvIn.ifEmpty { null }?.get(0)?.keys ?: emptySet()


                val cls: KClass<out CSVConvertible<*>>? = headerMap[headers]

                cls?.let {
                    try {
                        imports[it] =
                            csvIn.mapNotNull { b: Map<String, String> ->
                                it.objectInstance?.fromCSV(
                                    b
                                )
                            }
                    } catch (e: Exception) {
                        exceptionHandler?.let { exc -> exc(e) }
                    }
                }

                nextEntry = zipIn.nextEntry
            }

            zipIn.closeEntry()

            action(imports)
        }
    }

    @Suppress("SameParameterValue")
    fun getContentLauncher(
        prefix: String,
        kList: List<CSVConvertiblePackImport<*, *>>,
        action: (ModelMap) -> Unit,
        exceptionHandler: ((Exception) -> Unit)? = null
    ): ActivityResultLauncher<String> =
        ctx.registerForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
            uri?.let {
                ctx.contentResolver.query(it, null, null, null, null)
                    ?.use { cursor ->
                        val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                        cursor.moveToFirst()
                        val fileName = cursor.getString(nameIndex)
                        if (fileName.startsWith(prefix)) {
                            try {
                                extractZip(it, kList, action, exceptionHandler)
                            } catch (e: SecurityException) {

                            }
                        }
                    }
            }
        }


    companion object {
        const val FILE_ZIP = "location_module_"

        private fun getZipFileName() =
            "$FILE_ZIP${LocalDate.now().toString().replace('-', '_')}.zip"

        var dtf: DateTimeFormatter =
            DateTimeFormatter.ofPattern("yyyy_MM_dd_HH:mm")//, Locale.getDefault())
    }
}

class CSVConvertiblePackExport<T : Any>(
    val items: List<T>,
    val fileName: String,
    val asList: (T) -> List<*>,
    val headerList: List<String>,
)

class CSVConvertiblePackImport<T : Any, U : CSVConvertible<T>>(
    val kClass: KClass<out U>,
    val headers: Set<String>,
)

/**
 * In order to import/export an entity, it must have a companion class which implements CSVConvertible
 *
 * @param T The class that will be converted to/from CSV
 */
interface CSVConvertible<T : Any> {
    /**
     * The name of the csv file to be exported. An underscore and the current datetime will be
     * appended to the name, plus the csv extension
     */
    val saveFileName: String

    /**
     * Gets a description of the objects' values to be imported/exported
     *
     * @return an array of [Column]s which describe the object properties to import/export
     */
    fun getColumns(): Array<Column<T>>

    /**
     * A function that will initialize an object from the values in [row]
     *
     * @param row A map of column headers to values
     * @return An object of type [T] initialized with the values from [row]
     */
    fun fromCSV(row: Map<String, String>): T

    val headerList: Set<String>
        get() = getColumns().map(Column<T>::headerName).toSet()

    private fun asList(item: T): List<*> {
        val res = mutableListOf<Any?>()
        getColumns().forEach {
            res.add(it.getValue(item))
        }
        return res
    }

    fun getConvertPackExport(items: List<T>): CSVConvertiblePackExport<T> =
        CSVConvertiblePackExport(
            items,
            saveFileName + "_" + LocalDateTime.now().format(dtf),
            ::asList,
            getColumns().map(Column<T>::headerName)
        )

    /**
     * Describes a csv column to be imported/exported
     *
     * @param T The class of object to be imported/exported
     * @property headerName The value that will describe the property in the csv file
     * @property getValue The property to be imported/exported
     */
    class Column<T : Any>(
        val headerName: String,
        val getValue: KProperty1<T, *>
    )
}

inline fun <reified T : Any, reified U : CSVConvertible<T>> U.getConvertPackImport(): CSVConvertiblePackImport<T, U> =
    CSVConvertiblePackImport(
        U::class,
        headerList
    )

/**
 * A wrapper for a mutable map of CSVConvertible<T> KClass to List<T>
 *
 */
class ModelMap {
    private val _modelMap: MutableMap<KClass<*>, List<*>> = mutableMapOf()
    val modelMap: Map<KClass<*>, List<*>>
        get() = _modelMap

    operator fun set(key: KClass<out Any>, value: List<Any>) {
        _modelMap[key] = value
    }

    operator fun get(cls: KClass<*>): List<*>? = modelMap[cls]

    @Suppress("UNCHECKED_CAST")
    inline fun <reified T : Any, reified U : CSVConvertible<T>> get(): List<T> =
        modelMap[U::class] as List<T>? ?: listOf()
}


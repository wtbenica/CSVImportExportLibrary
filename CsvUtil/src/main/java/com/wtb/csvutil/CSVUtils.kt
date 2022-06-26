/*
 * Copyright 2022 Wesley T. Benica
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.wtb.csvutil

import android.content.Context
import android.content.Intent
import android.net.Uri
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

/**
 * Contains functions that allow converting objects to/from CSV
 *
 * @property activity
 */
class CSVUtils(private val activity: AppCompatActivity) {
    /**
     * Creates CSV files for the items described in [exportPacks], zips those files, then launches a
     * chooser from [activity] to save the zip file
     *
     * @param exportPacks call [CSVConvertible.getConvertPackExport] on the items you would like to
     * export
     */
    fun export(vararg exportPacks: CSVConvertiblePackExport<*>) {
        /**
         * Creates a [File] named [fileName] in the directory returned by [Context.getFilesDir]
         *
         * @param context
         * @param fileName the name for the file to be created
         * @return the created file, null if it was not created
         */
        fun generateFile(context: Context, fileName: String): File? {
            val csvFile = File(context.filesDir, fileName)
            csvFile.createNewFile()

            return if (csvFile.exists()) {
                csvFile
            } else {
                null
            }
        }

        /**
         * Creates a zip file containing the files passed in [files]
         *
         * @param context
         * @param files the files to be zipped
         * @return a zip file containing the files in [files]
         */
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

        /**
         * Creates an [Intent.ACTION_SEND] intent for [file]
         *
         * @param file the [File] to be sent
         * @return an [Intent.ACTION_SEND] intent for [file]
         */
        fun getSendFilesIntent(file: File): Intent {
            val intent = Intent(Intent.ACTION_SEND)
            val contentUri = FileProvider.getUriForFile(
                activity, "${activity.packageName}.fileprovider", file
            )
            intent.data = contentUri
            intent.putExtra(Intent.EXTRA_STREAM, contentUri)
            intent.flags =
                Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION

            return intent
        }

        /**
         * Converts the objects in [convert] and writes them to [csvFile]
         *
         * @param T the class of the objects to be exported
         * @param csvFile the file to write the objects' data to
         * @param convert the [CSVConvertiblePackExport] for the objects to be exported
         */
        fun <T : Any> exportData(csvFile: File, convert: CSVConvertiblePackExport<T>) {
            csvWriter().open(csvFile, append = false) {
                writeRow(convert.headerList)
                convert.items.ifEmpty { null }?.forEach {
                    writeRow(convert.asList(it))
                }
            }
        }

        val csvFiles = mutableListOf<File>()

        exportPacks.forEach {
            val outFile: File? = generateFile(activity, it.fileName)

            if (outFile != null) {
                exportData(outFile, it)
                csvFiles.add(outFile)
            } else {
                activity.runOnUiThread {
                    Toast.makeText(
                        activity,
                        "Error creating file ${it.fileName}",
                        Toast.LENGTH_LONG
                    ).show()
                }
            }
        }

        val zipFile: File = zipFiles(activity, *csvFiles.toTypedArray())

        csvFiles.forEach { it.delete() }

        activity.runOnUiThread {
            ContextCompat.startActivity(
                activity,
                Intent.createChooser(getSendFilesIntent(zipFile), null),
                null
            )
        }
    }

    /**
     * Launches the [activityResultLauncher] returned by [getContentLauncher]. It will launch a
     * prompt for the user to select a file to import, and will handle the result of the import
     * using the values passed to [getContentLauncher]
     *
     * @param activityResultLauncher
     */
    fun import(activityResultLauncher: ActivityResultLauncher<String>) {
        activity.runOnUiThread {
            activityResultLauncher.launch("application/zip")
        }
    }

    /**
     * Creates an [ActivityResultLauncher] that will prompt the user to choose a file when it is
     * launched, and handle the objects imported from the file using [action]. This must be called
     * as part of [activity]'s initialization and the result passed to [import] when it is called
     *
     * @param importPacks call [getConvertPackImport] on the classes of the objects to be imported
     * @param action a handler for the [ModelMap] t
     * @param exceptionHandler a handler for any exceptions that are raised by [action]
     * @return an [ActivityResultLauncher] that is needed to call [import]
     */
    fun getContentLauncher(
        importPacks: List<CSVConvertiblePackImport<*, *>>,
        action: (ModelMap) -> Unit,
        exceptionHandler: ((Exception) -> Unit)? = null
    ): ActivityResultLauncher<String> =
        activity.registerForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
            uri?.let {
                activity.contentResolver.query(it, null, null, null, null)
                    ?.use { cursor ->
                        cursor.moveToFirst()
                        try {
                            extractZip(it, importPacks, action, exceptionHandler)
                        } catch (e: SecurityException) {

                        }
                    }
            }
        }

    private fun extractZip(
        uri: Uri,
        importPacks: List<CSVConvertiblePackImport<*, *>>,
        action: (ModelMap) -> Unit,
        exceptionHandler: ((Exception) -> Unit)?
    ) {
        fun extractToFileInputStream(nextEntry: ZipEntry, zipIn: ZipInputStream): FileInputStream {
            val res = File(activity.filesDir, nextEntry.name)
            if (!res.canonicalPath.startsWith(activity.filesDir.canonicalPath)) {
                throw SecurityException()
            }
            FileOutputStream(res).use { t ->
                zipIn.copyTo(t, 1024)
            }
            return FileInputStream(res)
        }

        ZipInputStream(activity.contentResolver.openInputStream(uri)).use { zipIn ->
            var nextEntry: ZipEntry? = zipIn.nextEntry
            val imports = ModelMap()
            val headerMap = mutableMapOf<Set<String>, KClass<out CSVConvertible<*>>>().apply {
                importPacks.forEach { ccpi ->
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
                        imports[it] = csvIn.mapNotNull { b: Map<String, String> ->
                            it.objectInstance?.fromCSV(b)
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

    companion object {
        const val FILE_ZIP = "location_module_"

        private fun getZipFileName() =
            "$FILE_ZIP${LocalDate.now().toString().replace('-', '_')}.zip"

        var dtf: DateTimeFormatter =
            DateTimeFormatter.ofPattern("yyyy_MM_dd_HH:mm")//, Locale.getDefault())
    }
}

/**
 * Stores a list of objects and the information required to export them.
 *
 * @param T the class of the objects being exported
 */
data class CSVConvertiblePackExport<T : Any>(
    val items: List<T>,
    val fileName: String,
    val asList: (T) -> List<*>,
    val headerList: List<String>,
)

/**
 * Stores a class and its property/column header names
 *
 * @param T the class of the objects being imported
 * @param U the CSVConvertible<T> companion object
 */
data class CSVConvertiblePackImport<T : Any, U : CSVConvertible<T>>(
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

    /**
     * Creates a [CSVConvertiblePackExport] that can be passed to [CSVUtils.export]
     *
     * @param items the items that will be exported
     * @return a [CSVConvertiblePackExport]
     */
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

/**
 * Creates a [CSVConvertiblePackImport] that can be passed to [CSVUtils.getContentLauncher]
 *
 * @param T the class being imported
 * @param U the [CSVConvertible] companion object of [T]
 * @return a [CSVConvertiblePackImport]
 */
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

    internal operator fun set(key: KClass<*>, value: List<*>) {
        _modelMap[key] = value
    }

    @Suppress("UNCHECKED_CAST")
    inline fun <reified T : Any, reified U : CSVConvertible<T>> get(): List<T> =
        modelMap[U::class] as List<T>? ?: listOf()
}
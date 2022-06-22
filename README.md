# CIELo - CSV Import/Export Library

1. add dependency kotlin-reflect
2. for any of the classes <T> which you want exported:
    1. add a companion object which implements CSVConvertible<T: Any>
    2. add saveFileName, getColumns(): Array<Column<T>>, and fromCSV(row: Map<String, String>): T
        1. saveFileName - what the csv file will be named. It will be appended with an underscore
           and the current date
        2. getColumns - A 'Column' contains the column header and the property accessor
        3. fromCSV - initialize an object from a map of column headers to values
    3. The recommended method is to create an enum where each enum value has the properties needed
       to create a 'Column'
        1. getColumns can be implemented then as EnumClass.values().map { Column(it.headerName,
           it.getProperty) }
        2. fromCSV can be implemented as:
            1. fromCSV(row): T = T(
                  a = row[Columns.A.headerName]?.convertFromStringToType(), 
                  b = row[Columns.B.headerName]?.convertFromStringToType(), ...
               )
        3. Doing this makes sure that column header values are consistent
3. To export:
    1. Create a CSVUtils instance, e.g. val csvUtil = CSVUtils(activity)
    2. Call export from the instance, with a list of CSVConvertiblePackExports as the arguments
    3. Each CSVConvertible class has an extension function getConvertPackExport(items: List<T>) that
       will create the ConvertiblePacks for you
4. To import:
    1. Create a CSVUtils instance, e.g. val csvUtil = CSVUtils(activity)
    2. Before the calling activity is in a resumed state, get an ActivityResultLauncher by calling
       csvUtil.getContentLauncher(prefix, klist, action, exceptionhandler)
    3. To import, pass the Launcher to csvUtil.import(launcher).
package org.tera201.vcsmanager.scm.entities

data class BlameManager(
    var fileMap: Map<String, BlameFileInfo>,
    var projectName: String
) {
    var packageMap: Map<String, BlamePackageInfo> = aggregateByPackage()
    var rootPackageInfo: BlamePackageInfo? = packageMap["/$projectName"]

    private fun aggregateByPackage(): Map<String, BlamePackageInfo> {
        val packageData = mutableMapOf<String, BlamePackageInfo>()

        fileMap.forEach { (filePath, fileInfo) ->
            var packagePath = "/$projectName/$filePath".substringBeforeLast('/', "")

            while (packagePath.isNotEmpty()) {
                val packageName = packagePath.substringAfterLast('/')
                packageData.computeIfAbsent(packagePath) { BlamePackageInfo(packageName) }
                    .add(fileInfo, packagePath)
                packagePath = packagePath.substringBeforeLast('/', "")
            }
        }

        return packageData
    }
}

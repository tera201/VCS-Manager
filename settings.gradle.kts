rootProject.name = "vcs-manager"

dependencyResolutionManagement {
    versionCatalogs {
        create("libs") {
            from(files("../../gradle/libs.versions.toml"))
        }
    }
}
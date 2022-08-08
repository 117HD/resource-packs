import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.kotlin.dsl.*

class ManifestPlugin : Plugin<Project> {

    override fun apply(project: Project): Unit = with(project) {
        tasks.register<ManifestTask>("update-manifest")
    }

}
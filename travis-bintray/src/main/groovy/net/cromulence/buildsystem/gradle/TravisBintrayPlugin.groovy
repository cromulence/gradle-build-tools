package net.cromulence.buildsystem.gradle

import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.UnknownDomainObjectException
import org.gradle.api.plugins.JavaPlugin
import org.gradle.api.publish.PublishingExtension
import org.gradle.api.publish.maven.MavenPublication
import org.gradle.api.publish.maven.plugins.MavenPublishPlugin
import org.gradle.api.tasks.Copy
import org.gradle.api.tasks.bundling.Jar

import java.text.SimpleDateFormat

class TravisBintrayPlugin implements Plugin<Project> {

    private static String EXTENSION_NAME = "TravisBintray"

    @Override
    void apply(final Project project) {

        project.logger.info("TravisBintray.apply for project " + project.name)

        if(isRootProject(project)) {
            createCopyBintrayTemplateTask(project)
        } else {
            project.apply(plugin: MavenPublishPlugin)

            project.afterEvaluate() {

                project.plugins.withType(JavaPlugin) {
                    println("Creating source and javadoc tasks on java project: " + project.getName())
                    createSourceJarTask(project)
                    createJavadocJarTask(project)
                }

                project.plugins.withType(MavenPublishPlugin) {
                    project.logger.debug("Creating publication on maven project: " + project.getName())

                    PublishingExtension pext = (PublishingExtension) project.getExtensions().getByName('publishing')
                    try {
                        MavenPublication pub = (MavenPublication) pext.publications.getByName('Maven')

                        println("Adding jars to artifacts for project: " + project.getName())
                        addSourceJarToArtifacts(project, pub)
                        addJavadocJarToArtifacts(project, pub)
                    } catch (UnknownDomainObjectException e) {
                        project.logger.info("Maven publication not present for project: " + project.getName(), e)
                    }
                }

                project.plugins.withType(JavaPlugin) {
                    project.logger.debug("withType(JavaPlugin) - ${project.name}")

                    if (project.plugins.hasPlugin(MavenPublishPlugin)) {
                        project.logger.debug("withType(JavaPlugin) AND hasPlugin(MavenPublishPlugin) - ${project.name}")
                    } else {
                        project.logger.debug("withType(JavaPlugin) AND NO MavenPublishPlugin - ${project.name}")
                    }

                    project.publishing.publications {
                        Maven(MavenPublication) {
                            from project.components.java
                        }
                    }
                }
            }
        }
    }

    static void addJavadocJarToArtifacts(Project project, MavenPublication publication) {
        if (project.tasks.findByName("javadocJar")) {
            project.logger.info("Adding javadoc jar task for project: " + project.getName())
            publication.artifact(project.tasks.javadocJar)
        }
    }

    static void addSourceJarToArtifacts(Project project, MavenPublication publication) {
        if (project.tasks.findByName("sourcesJar")) {
            project.logger.info("Adding source jar for project: " + project.getName())
            publication.artifact(project.tasks.sourcesJar)
        }
    }

    private void createJavadocJarTask(Project project) {

        project.logger.debug("Adding javadocJar task on project: " + project.getName())
        project.task("javadocJar", type: Jar, dependsOn: project.tasks.javadoc) {
            baseName = project.archivesBaseName
            classifier 'javadoc'
            from project.javadoc.destinationDir
        }
    }

    private void createSourceJarTask(Project project) {

        project.logger.debug("creating sourceJar task on project: " + project.getName())
        project.task("sourcesJar", type: Jar) {
            baseName = project.archivesBaseName
            classifier 'sources'
            from project.sourceSets.main.allSource
        }
    }

    private void createCopyBintrayTemplateTask(final Project project) {
        project.logger.debug("creating copyBintrayTemplate task on project: " + project.getName())

        final String projectVersion = projectVersion(project)

        final String filesToUpload = filesToUpload(project)

        final String desc = getProjectProperty(project, "desc")
        final String githubUser = getProjectProperty(project, "githubUser")
        final String githubRepo = getProjectProperty(project, "githubRepo")

        project.task("copyBintrayTemplate", type: Copy){
            from project.zipTree(project.buildscript.configurations.getByName("classpath").
                filter({it.name.startsWith('travis-bintray')}).singleFile)
            into project.getBuildDir()
            include "bintray.json.template"
            rename { file -> 'bintray.json' }
            expand(version: projectVersion, date: releaseDate(), tag: tagName(), files: filesToUpload, desc: desc,
                githubUser: githubUser, githubRepo: githubRepo)
        }
    }

    static def isRootProject(Project p) {
        return p == p.getRootProject()
    }

    static def filesToUpload(Project rootProject) {

        def sourceProjects = rootProject.getSubprojects()

        StringBuffer sb = new StringBuffer()

        for (Project p : sourceProjects) {
            def uploadDir  = gavToPath(p) + p.getName() + "/" + projectVersion(p) + "/"
            def jarUploadPattern = uploadDir + "\$1"
            def pomUploadPattern = uploadDir + p.getName() + "-" + projectVersion(p) + ".pom"
            sb.append("        {\"includePattern\" : \"").append(p.getPath().substring(1).replaceAll(":", "/")).append("/build/libs/(.*jar)\",\"uploadPattern\": \"").append(jarUploadPattern).append("\"},\n")
            sb.append("        {\"includePattern\" : \"").append(p.getPath().substring(1).replaceAll(":", "/")).append("/build/publications/Maven/pom-default.xml\",\"uploadPattern\": \"").append(pomUploadPattern).append("\"},\n")
        }

        // remove last comma
        sb.deleteCharAt(sb.length() - 1)
        sb.deleteCharAt(sb.length() - 1)
        sb.append("\n")

        return sb.toString()
    }

    static def gavToPath(Project p) {
        // net.cromulence.foo.bar > net/cromulence/foo/bar
        return getProjectProperty(p, "gavGroup").replace(".", "/") + "/"
    }

    static def getProjectProperty(Project p, String name) {
        return p.getProperties().get(name)
    }

    static def projectVersion(Project rootProject) {

        String majorMinorVersion = getProjectProperty(rootProject, "majorMinorVersion")

        String msg = "no message set"

        // is there a travis tag?
        if (System.getenv("TRAVIS_TAG") != null && System.getenv("TRAVIS_TAG").length() > 0) {
            // we are building a tag on travis
            String tagName = System.getenv("TRAVIS_TAG")

            msg = "Travis tag build"

            if (tagName.startsWith(majorMinorVersion)) {
                //we're building a tag in line with the current expected version'
                rootProject.setProperty("version", tagName)
                msg = "Travis tag build: version as expected"
            } else {
                // name is messed up
                rootProject.setProperty("version", majorMinorVersion + ".${tagName}")
                msg = "Travis tag build: version not as expected"
            }
        } else if (System.getenv("TRAVIS") != null && System.getenv("TRAVIS").length() > 0) {
            // travis master build
            rootProject.setProperty("version", majorMinorVersion + "-SNAPSHOT")
            msg = "Travis master build"
        } else {
            //local build
            rootProject.setProperty("version", majorMinorVersion + "-SNAPSHOT")
            msg = "Local build"
        }

        rootProject.logger.info(msg + ": " + getProjectProperty(rootProject, "version"))
        return getProjectProperty(rootProject, "version")
    }

    static def releaseDate() {
        new SimpleDateFormat("yyyy-MM-dd").format(new Date())
    }

    static def tagName() {
        return System.getenv("TRAVIS_TAG")
    }
}

import groovy.json.*
import java.util.*
import hudson.Util

def call(currentBuild, envs) {
    def currentStage = ""
    def git_changes = ""
    def changeLog = ""
    def latest_commit = ""
    def passedBuilds = []
    def items = envs.someList.split(',').toList()
    try {
        def _properties = []
        // Specify and load job properties
        _properties.add([$class: 'jenkins.model.BuildDiscarderProperty', strategy: [$class: 'LogRotator', numToKeepStr: '50', artifactNumToKeepStr: '5']])
        _properties.add(disableConcurrentBuilds())
        properties(_properties)

        node {
            env.CD_REGISTRY_IMAGE = env.CD_REGISTRY + '/' + env.CD_SERVICE_NAME
            env.IMAGE_TAG = env.BRANCH_NAME + '-' + env.BUILD_NUMBER

            stage('Git Checkout') {
                // Useful commands to make your pipeline outputs more understandable
                currentStage = "Git Checkout"
                checkout scm
                sha = sh(script: 'git rev-parse --short HEAD', returnStdout: true).trim()
                committer = sh(script: "git --no-pager log -1 --pretty=format:'%an::%ae' ${sha}", returnStdout: true).trim()
                latest_commit = sh(script: 'git log -n 1 --pretty="%s"', returnStdout: true)
                lastSuccessfulBuild(passedBuilds, currentBuild)
                changeLog = getChangeLog(passedBuilds)
                git_changes = sh (script: 'git log -n 1 --pretty="%h %s [%an] %n %b"', returnStdout: true)
                env.COMMITTER_NAME = committer.tokenize('::')[0]
                env.COMMITTER_EMAIL = committer.tokenize('::')[1]
                env.COMMITTER_USERNAME = env.COMMITTER_EMAIL.tokenize('@')[0]
            }

            stage('Build') {
                // Somewhat regular Java application build process
                currentStage = "Build"
                sh "mvn clean install"
                junit '**/target/surefire-reports/TEST-*.xml'
                junit '**/target/jest-reports/*.xml'
                archiveArtifacts(artifacts: '**/target/*.jar,**/target/*.war', fingerprint: true, onlyIfSuccessful: true)
                sh """

                    docker build -t "$CD_REGISTRY_IMAGE:$IMAGE_TAG" .
                    docker push "$CD_REGISTRY_IMAGE:$IMAGE_TAG"
                """
            }

                // Option to run parallel jobs that are built on the fly
                // There is an option to restrict parallel job number
                def itemsPerStage = 5
                def numberOfStages = (items.size().intdiv(itemsPerStage))
                if ((items.size()) % itemsPerStage != 0)
                    numberOfStages++

                // Creating stages according to itemsPerStage
                for (int i = 0; i < numberOfStages; i++) {
                    stage("Deploy") {
                        currentStage = "Deploy"
                        stepsForParallel = [:]
                        def length = items.size()
                        for (int x = 0; x < length; x++) {
                            def item = items.pop()
                            def stepName = "Deploy ${item}"
                            stepsForParallel[stepName] = transformIntoStep(item)
                            if (x == (itemsPerStage - 1))
                                break
                        }
                        parallel stepsForParallel
                    }
                }

                //Another option for parallel jobs is manually specifying them
                def tasks = [:]
                tasks["API test 1"] = {
                    sh "./gradlew clean SomeSanitytest1"
                }
                tasks["API test 2"] = {
                    sh "./gradlew clean SomeSanitytest2"
                }
                parallel tasks

            // Send out detailed massage to slack
            slackSend(
                    channel: '#deployments',
                    color: 'good',
                    message: ":java: *${env.JOB_NAME}* - <${RUN_CHANGES_DISPLAY_URL}|#${env.BUILD_NUMBER}> Succeeded after ${Util.getTimeSpanString(System.currentTimeMillis() - currentBuild.startTimeInMillis)} (<${RUN_DISPLAY_URL}|open>) \n" +
                            "${currentBuild.rawBuild.getCauses()[0].getShortDescription()} \n" +
                            "Changes: \n $changeLog"
            )
        }
    } catch (e) {
                // Send out detailed  massage to slack
                slackSend(
                        channel: '#pipeline',
                        color: 'danger',
                        message: ":java: *${env.JOB_NAME}* - <${RUN_CHANGES_DISPLAY_URL}|#${env.BUILD_NUMBER}> Failed on stage ${currentStage} after ${Util.getTimeSpanString(System.currentTimeMillis() - currentBuild.startTimeInMillis)} (<${RUN_DISPLAY_URL}|open>) \n" +
                                "${currentBuild.rawBuild.getCauses()[0].getShortDescription()} \n" +
                                "Last Commit: \n $git_changes"
                )

                // Send out email to certain recipients
                recipients = "xxx@xxx.com"
                body = 'Check console output at "<a href="${env.BUILD_URL}">${env.JOB_NAME} [${env.BUILD_NUMBER}]</a>"'
                emailext(
                        mimeType: 'text/html',
                        to: recipients,
                        subject: "FAILED: Job ${env.JOB_NAME} [${env.BUILD_NUMBER}]:",
                        body: body
                )
        throw e
        }
}

// Get last successful build of this pipeline
def lastSuccessfulBuild(passedBuilds, build) {
    if ((build != null) && (build.result != 'SUCCESS')) {
        passedBuilds.add(build)
        lastSuccessfulBuild(passedBuilds, build.getPreviousBuild())
    }
}

// Get details git log of developer changes
def getChangeLog(passedBuilds) {
    def log = ""
    for (int x = 0; x < passedBuilds.size(); x++) {
        def currentBuild = passedBuilds[x];
        def changeLogSets = currentBuild.rawBuild.changeSets
        for (int i = 0; i < changeLogSets.size(); i++) {
            def entries = changeLogSets[i].items
            for (int j = 0; j < entries.length; j++) {
                def entry = entries[j]
                log += "* ${entry.msg} [${entry.author}] \n"
            }
        }
    }
    return log
}

def transformIntoStep(item) {
    return {
        ansiColor('xterm') { // Make the output more colorful
            // Example of Kubernetes deployment
            sh "/usr/local/bin/kubectl set image deployment ${item} $CI_REGISTRY_IMAGE=$CI_REGISTRY_IMAGE:$IMAGE_TAG --record=true"
            timeout(time: 500, unit: 'SECONDS') {
                sh "/usr/local/bin/kubectl rollout status deployment ${item}"
            }
        }
    }
}
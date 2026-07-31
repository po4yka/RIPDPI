internal fun requestedApplicationBundleTasks(
    taskNames: List<String>,
    projectPath: String,
): List<String> =
    taskNames.filter { taskName ->
        val normalizedTaskName = taskName.removePrefix(":")
        val taskPathParts = normalizedTaskName.split(':')
        val taskProjectPath =
            if (taskPathParts.size == 1) {
                projectPath
            } else {
                ":" + taskPathParts.dropLast(1).joinToString(":")
            }
        val taskLeafName = taskPathParts.last()

        taskProjectPath == projectPath && taskLeafName.startsWith("bundle")
    }

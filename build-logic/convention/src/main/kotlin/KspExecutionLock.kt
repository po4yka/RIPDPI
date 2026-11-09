import org.gradle.api.services.BuildService
import org.gradle.api.services.BuildServiceParameters

abstract class KspExecutionLock : BuildService<BuildServiceParameters.None>

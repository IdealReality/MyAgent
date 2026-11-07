$env:JAVA_HOME = 'C:\Program Files\Java\jdk-17.0.12'
$env:ANDROID_HOME = 'C:\Users\guest\AppData\Local\Android\Sdk'
Set-Location 'A:\Dev\MyAgent'
& .\gradlew.bat :app:testDebugUnitTest --tests 'me.rerere.rikkahub.data.ai.tools.ToolCatalog*' --no-daemon --console=plain -x :web:installWebUiDeps -x :web:buildWebUi *> A:\Dev\MyAgent\test-catalog.log
exit $LASTEXITCODE
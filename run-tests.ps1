$env:JAVA_HOME = 'C:\Program Files\Java\jdk-17.0.12'
$env:ANDROID_HOME = 'C:\Users\guest\AppData\Local\Android\Sdk'
Set-Location 'A:\Dev\MyAgent'
& .\gradlew.bat :app:testDebugUnitTest --no-daemon --console=plain -x :web:installWebUiDeps -x :web:buildWebUi *> A:\Dev\MyAgent\test-full.log
exit $LASTEXITCODE
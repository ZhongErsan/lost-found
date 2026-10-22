# Compile only these focused tests: older conversation tests still use obsolete assistant signatures.
$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path -Parent $PSScriptRoot
$temporaryPom = Join-Path $projectRoot '.persistent-index-tests-pom.xml'
if (Test-Path -LiteralPath $temporaryPom) { throw "Temporary POM already exists: $temporaryPom" }
Push-Location $projectRoot
try {
    [xml]$document = Get-Content -LiteralPath (Join-Path $projectRoot 'pom.xml') -Raw
    $ns = $document.DocumentElement.NamespaceURI
    $compiler = $document.project.build.plugins.plugin | Where-Object { $_.artifactId -eq 'maven-compiler-plugin' }
    $configuration = $document.CreateElement('configuration', $ns)
    $includes = $document.CreateElement('testIncludes', $ns)
    foreach ($pattern in @('**/LostPropertyIndexTests.java', '**/LostPropertyIndexIntegrationTests.java')) {
        $include = $document.CreateElement('testInclude', $ns)
        $include.InnerText = $pattern
        [void]$includes.AppendChild($include)
    }
    [void]$configuration.AppendChild($includes)
    [void]$compiler.AppendChild($configuration)
    $document.Save($temporaryPom)
    $ErrorActionPreference = 'Continue' # JVM warnings on stderr are not a test failure.
    & mvn -f $temporaryPom "-Dmaven.repo.local=$env:USERPROFILE\.m2\repository" -o '-Dtest=LostPropertyIndexTests,LostPropertyIndexIntegrationTests' test
    $result = $LASTEXITCODE
} finally {
    if (Test-Path -LiteralPath $temporaryPom) { Remove-Item -LiteralPath $temporaryPom }
    Pop-Location
}
exit $result

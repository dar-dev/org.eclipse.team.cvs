# Compile CVS against an installed Eclipse and run the standalone compatibility tests.
# This does not modify the Eclipse installation or require a CVS server.
# SPDX-License-Identifier: EPL-2.0
param(
    [Parameter(Mandatory = $true)]
    [string]$EclipseHome,
    [string]$JavaHome = $env:JAVA_HOME,
    [string]$OutputDirectory
)

$ErrorActionPreference = 'Stop'
$repo = Split-Path $PSScriptRoot -Parent
$EclipseHome = (Resolve-Path $EclipseHome).Path
$info = Join-Path $EclipseHome 'configuration/org.eclipse.equinox.simpleconfigurator/bundles.info'
$bundles = Import-Csv $info -Header Name, Version, Location, Level, Started |
    Where-Object { $_.Location -and $_.Name -notlike 'org.eclipse.team.cvs*' }
$paths = @($bundles | ForEach-Object {
    if ($_.Location -like 'file:*') {
        ([uri]$_.Location).LocalPath
    } else {
        Join-Path $EclipseHome $_.Location
    }
} | Where-Object { Test-Path $_ -PathType Leaf })

if ($JavaHome) {
    $javac = Join-Path $JavaHome 'bin/javac.exe'
    $java = Join-Path $JavaHome 'bin/java.exe'
} else {
    $javac = (Get-Command javac.exe).Source
    $java = (Get-Command java.exe).Source
}
if ($OutputDirectory) {
    $work = [IO.Path]::GetFullPath($OutputDirectory)
    if (Test-Path $work) { throw "Output directory already exists: $work" }
} else {
    $work = Join-Path ([IO.Path]::GetTempPath()) ('cvs-compat-' + [guid]::NewGuid())
}
New-Item -ItemType Directory $work | Out-Null
$utf8 = New-Object System.Text.UTF8Encoding($false)

function Invoke-JavaTool($tool, $arguments, $name) {
    # Argument files avoid Windows command-line length limits for the classpath.
    $argsFile = Join-Path $work "$name.args"
    [IO.File]::WriteAllLines($argsFile, $arguments, $utf8)
    $previous = $ErrorActionPreference
    $ErrorActionPreference = 'Continue' # javac warnings on stderr are not failures
    try {
        & $tool "@$argsFile"
        $code = $LASTEXITCODE
    } finally {
        $ErrorActionPreference = $previous
    }
    if ($code -ne 0) { throw "$name failed (exit $code). Output retained in $work" }
}

$classpath = $paths -join ';'
foreach ($bundle in @('core', 'ssh2', 'ui')) {
    $source = Join-Path $repo "bundles/org.eclipse.team.cvs.$bundle/src"
    $output = Join-Path $work $bundle
    $arguments = @('--release', '17', '-encoding', 'UTF-8', '-cp',
        ('"' + $classpath.Replace('\', '/') + '"'), '-d',
        ('"' + $output.Replace('\', '/') + '"'))
    $arguments += @(Get-ChildItem $source -Recurse -Filter '*.java' | ForEach-Object {
        '"' + $_.FullName.Replace('\', '/') + '"'
    })
    Invoke-JavaTool $javac $arguments "compile-$bundle"
    # Resource bundles must accompany the compiled stream/UI helpers.
    Get-ChildItem $source -Recurse -Filter '*.properties' | ForEach-Object {
        $destination = Join-Path $output $_.FullName.Substring($source.Length + 1)
        New-Item -ItemType Directory -Force (Split-Path $destination -Parent) | Out-Null
        Copy-Item $_.FullName $destination
    }
    $classpath = "$output;$classpath"
    Write-Host "Compiled org.eclipse.team.cvs.$bundle"
}

# Eclipse must contain JUnit 3 or 4 (normally provided by PDE).
$test = Join-Path $repo 'tests/org.eclipse.team.tests.cvs.core/src/org/eclipse/team/tests/ccvs/core/CompatibilityTests.java'
$output = Join-Path $work 'tests'
Invoke-JavaTool $javac @('--release', '17', '-encoding', 'UTF-8', '-cp',
    ('"' + $classpath.Replace('\', '/') + '"'), '-d',
    ('"' + $output.Replace('\', '/') + '"'), ('"' + $test.Replace('\', '/') + '"')) 'compile-tests'
Invoke-JavaTool $java @('-cp', ('"' + ("$output;$classpath").Replace('\', '/') + '"'),
    'junit.textui.TestRunner', 'org.eclipse.team.tests.ccvs.core.CompatibilityTests') 'run-tests'
Write-Host "Compatibility checks passed. Compiled output: $work"

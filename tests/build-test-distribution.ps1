# Build an unsigned local p2 update site without modifying the Eclipse installation.
# SPDX-License-Identifier: EPL-2.0
param(
    [Parameter(Mandatory = $true)]
    [string]$EclipseHome,
    [string]$JavaHome = $env:JAVA_HOME,
    [string]$Qualifier = ((Get-Date -Format 'yyyyMMddHHmmss') + '-test')
)

$ErrorActionPreference = 'Stop'
if ($Qualifier -notmatch '^[A-Za-z0-9_-]+$') { throw 'Invalid OSGi version qualifier' }
$repo = Split-Path $PSScriptRoot -Parent
$EclipseHome = (Resolve-Path $EclipseHome).Path
$root = Join-Path $repo "target/cvs-test-$Qualifier"
if (Test-Path $root) { throw "Build directory already exists: $root" }
New-Item -ItemType Directory $root | Out-Null
$classes = Join-Path $root 'classes'
& "$PSScriptRoot/verify-compatibility.ps1" -EclipseHome $EclipseHome -JavaHome $JavaHome -OutputDirectory $classes

if ($JavaHome) {
    $jar = Join-Path $JavaHome 'bin/jar.exe'
    $java = Join-Path $JavaHome 'bin/java.exe'
} else {
    $jar = (Get-Command jar.exe).Source
    $java = (Get-Command java.exe).Source
}
$utf8 = New-Object System.Text.UTF8Encoding($false)
$site = Join-Path $root 'repository'
New-Item -ItemType Directory "$site/plugins", "$site/features" | Out-Null
$versions = @{}

function Copy-BinaryResources($source, $destination) {
    # Read PDE bin.includes, including continuation lines.
    $properties = [IO.File]::ReadAllText((Join-Path $source 'build.properties')) -replace '\\\r?\n\s*', ''
    $match = [regex]::Match($properties, '(?m)^bin\.includes\s*=\s*(.+)$')
    if (-not $match.Success) { throw "No bin.includes in $source" }
    foreach ($entry in $match.Groups[1].Value.Split(',')) {
        $entry = $entry.Trim()
        if ($entry -eq '.') { continue } # compiled classes are copied separately
        $path = Join-Path $source $entry
        if (-not (Test-Path $path)) { throw "Missing binary resource: $path" }
        Copy-Item $path $destination -Recurse -Force
    }
}

foreach ($id in @('org.eclipse.cvs', 'org.eclipse.team.cvs.core', 'org.eclipse.team.cvs.ssh2', 'org.eclipse.team.cvs.ui')) {
    $source = Join-Path $repo "bundles/$id"
    $payload = Join-Path $root "payload/$id"
    New-Item -ItemType Directory $payload | Out-Null
    Copy-BinaryResources $source $payload
    if ($id -ne 'org.eclipse.cvs') {
        $kind = $id.Substring('org.eclipse.team.cvs.'.Length)
        Copy-Item "$classes/$kind/*" $payload -Recurse -Force
    }
    $manifest = [IO.File]::ReadAllText((Join-Path $source 'META-INF/MANIFEST.MF'))
    $manifest = $manifest.Replace('.qualifier', ".$Qualifier")
    $version = [regex]::Match($manifest, '(?m)^Bundle-Version:\s*(\S+)').Groups[1].Value
    $versions[$id] = $version
    $manifestFile = Join-Path $root "$id.MF"
    [IO.File]::WriteAllText($manifestFile, $manifest, $utf8)
    Remove-Item "$payload/META-INF/MANIFEST.MF"
    & $jar --create --file "$site/plugins/${id}_$version.jar" --manifest $manifestFile -C $payload .
    if ($LASTEXITCODE -ne 0) { throw "Could not package $id" }
}

$featureSource = Join-Path $repo 'features/org.eclipse.cvs-feature'
$featurePayload = Join-Path $root 'payload/feature'
New-Item -ItemType Directory $featurePayload | Out-Null
Copy-BinaryResources $featureSource $featurePayload
$featurePath = Join-Path $featurePayload 'feature.xml'
[xml]$feature = [IO.File]::ReadAllText($featurePath)
$featureVersion = $feature.feature.version.Replace('.qualifier', ".$Qualifier")
$feature.feature.version = $featureVersion
foreach ($plugin in $feature.feature.plugin) { $plugin.version = $versions[$plugin.id] }
$feature.Save($featurePath)
& $jar --create --file "$site/features/org.eclipse.cvs_$featureVersion.jar" -C $featurePayload .
if ($LASTEXITCODE -ne 0) { throw 'Could not package CVS feature' }

# Use the installed publisher bundles with an isolated OSGi configuration/workspace.
$info = Join-Path $EclipseHome 'configuration/org.eclipse.equinox.simpleconfigurator/bundles.info'
$bundles = Import-Csv $info -Header Name, Version, Location, Level, Started
function Bundle-Path($name) {
    $bundle = $bundles | Where-Object Name -eq $name | Select-Object -First 1
    if (-not $bundle) { throw "Required Eclipse bundle is not installed: $name" }
    if ($bundle.Location -like 'file:*') { return ([uri]$bundle.Location).LocalPath }
    return Join-Path $EclipseHome $bundle.Location
}
function File-Uri($path) { return ([uri]([IO.Path]::GetFullPath($path))).AbsoluteUri }
$launcher = Bundle-Path 'org.eclipse.equinox.launcher'
$framework = File-Uri (Bundle-Path 'org.eclipse.osgi')
$simple = File-Uri (Bundle-Path 'org.eclipse.equinox.simpleconfigurator')
$null = Bundle-Path 'org.eclipse.equinox.p2.publisher.eclipse'
$config = Join-Path $root 'publisher-configuration'
New-Item -ItemType Directory $config | Out-Null
# Remove installed CVS from this temporary runtime, avoiding activation of the old code.
$publisherInfo = Join-Path $config 'bundles.info'
$lines = @('#version=1') + @($bundles | Where-Object {
    $_.Name -notlike '#*' -and $_.Name -ne 'org.eclipse.cvs' -and $_.Name -notlike 'org.eclipse.team.cvs*'
} | ForEach-Object {
    $location = File-Uri (Bundle-Path $_.Name)
    # Only bootstrap services should start automatically in the publisher runtime.
    $bootstrap = $_.Name -like 'org.eclipse.equinox.*' -or
        $_.Name -eq 'org.eclipse.core.runtime' -or $_.Name -eq 'org.apache.felix.scr'
    $started = if ($bootstrap) { $_.Started } else { 'false' }
    "$($_.Name),$($_.Version),$location,$($_.Level),$started"
})
[IO.File]::WriteAllLines($publisherInfo, $lines, $utf8)
$settings = @(
    "osgi.framework=$framework",
    "osgi.bundles=reference:$simple@1:start",
    'osgi.bundles.defaultStartLevel=4',
    ('org.eclipse.equinox.simpleconfigurator.configUrl=' + (File-Uri $publisherInfo))
)
[IO.File]::WriteAllLines((Join-Path $config 'config.ini'), $settings, $utf8)
$repositoryUri = File-Uri $site
$publisherInput = Join-Path $root 'publisher-input'
New-Item -ItemType Directory $publisherInput | Out-Null
Copy-Item "$site/plugins", "$site/features" $publisherInput -Recurse
$previous = $ErrorActionPreference
$ErrorActionPreference = 'Continue' # Eclipse may log informational messages on stderr
try {
    & $java -jar $launcher -nosplash -consoleLog -configuration $config -data "$root/publisher-workspace" `
        -application org.eclipse.equinox.p2.publisher.FeaturesAndBundlesPublisher `
        -source $publisherInput -metadataRepository $repositoryUri -artifactRepository $repositoryUri `
        -metadataRepositoryName 'CVS Eclipse 4.42 compatibility test' `
        -artifactRepositoryName 'CVS Eclipse 4.42 compatibility test' -publishArtifacts -compress
    $code = $LASTEXITCODE
} finally {
    $ErrorActionPreference = $previous
}
if ($code -ne 0) { throw 'p2 publisher failed' }
if (-not (Test-Path "$site/content.jar") -or -not (Test-Path "$site/artifacts.jar")) {
    throw 'Publisher did not create p2 repository metadata'
}

# Include modified sources and license alongside the installable repository.
$sourcePayload = Join-Path $root 'sources'
New-Item -ItemType Directory $sourcePayload | Out-Null
Copy-Item "$repo/bundles", "$repo/features", "$repo/docs", "$repo/LICENSE", "$repo/README.md" $sourcePayload -Recurse
Copy-Item "$repo/tests" $sourcePayload -Recurse
# jar uses portable forward-slash ZIP entries (Windows Compress-Archive may not).
& $jar --create --file "$site/cvs-sources.zip" --no-manifest -C $sourcePayload .
if ($LASTEXITCODE -ne 0) { throw 'Could not package sources' }
[IO.File]::WriteAllText((Join-Path $site 'INSTALL.txt'), @"
CVS Eclipse 4.42 compatibility test build
Feature: org.eclipse.cvs $featureVersion

In Eclipse: Help > Install New Software > Add > Archive, then select the update-site ZIP.
Uncheck 'Group items by category' if the feature is not visible.
This unsigned test build will prompt for trust confirmation. Restart after installation.
Use a backed-up or separate test workspace; live CVS integration has not been verified.
Sources (EPL-2.0): cvs-sources.zip in this repository.
"@, $utf8)
$zip = Join-Path $repo "target/eclipse-cvs-4.42-$Qualifier.zip"
& $jar --create --file $zip --no-manifest -C $site .
if ($LASTEXITCODE -ne 0) { throw 'Could not package update-site ZIP' }
Write-Host "Update-site ZIP: $zip"
Write-Host "Local repository: $site"
Write-Host "Feature version: $featureVersion"

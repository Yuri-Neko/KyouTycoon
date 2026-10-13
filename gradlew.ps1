param(
    [Parameter(ValueFromRemainingArguments = $true)]
    [string[]] $GradleArgs
)

$ErrorActionPreference = 'Stop'
$GradleVersion = '8.14'
$UserHome = if ($env:GRADLE_USER_HOME) { $env:GRADLE_USER_HOME } else { Join-Path $HOME '.gradle' }
$CacheRoot = Join-Path $UserHome 'kyoutycoon'
$InstallRoot = Join-Path $CacheRoot "gradle-$GradleVersion"
$GradleBin = Join-Path $InstallRoot 'bin/gradle.bat'

if (-not (Test-Path $GradleBin)) {
    New-Item -ItemType Directory -Path $CacheRoot -Force | Out-Null
    $TempRoot = Join-Path $CacheRoot ("bootstrap-" + [guid]::NewGuid().ToString('N'))
    New-Item -ItemType Directory -Path $TempRoot -Force | Out-Null
    try {
        $Archive = Join-Path $TempRoot 'gradle.zip'
        $Url = "https://services.gradle.org/distributions/gradle-$GradleVersion-bin.zip"
        Invoke-WebRequest -UseBasicParsing -Uri $Url -OutFile $Archive
        Expand-Archive -LiteralPath $Archive -DestinationPath $TempRoot -Force
        if (Test-Path $InstallRoot) { Remove-Item -LiteralPath $InstallRoot -Recurse -Force }
        Move-Item -LiteralPath (Join-Path $TempRoot "gradle-$GradleVersion") -Destination $InstallRoot
    }
    finally {
        if (Test-Path $TempRoot) { Remove-Item -LiteralPath $TempRoot -Recurse -Force }
    }
}

& $GradleBin @GradleArgs
exit $LASTEXITCODE

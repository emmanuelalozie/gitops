# Checks this machine for what the project needs and says what, if anything, to install.
# Run from the repository in PowerShell:  .\scripts\doctor.ps1
# If scripts are blocked:                 powershell -ExecutionPolicy Bypass -File .\scripts\doctor.ps1

$script:fail = 0
function Pass($text) { Write-Host '  ok    ' -ForegroundColor Green -NoNewline; Write-Host $text }
function Note($text) { Write-Host '  note  ' -ForegroundColor Yellow -NoNewline; Write-Host $text }
function Miss($text) { Write-Host '  fix   ' -ForegroundColor Red -NoNewline; Write-Host $text; $script:fail++ }
function Has($name) { [bool](Get-Command $name -ErrorAction SilentlyContinue) }

# The major version of a java binary, e.g. 21 (1.8 counts as 8). $null if it can't be read.
function JavaMajor($java) {
    $out = (& $java -version 2>&1 | Out-String)
    if ($out -match 'version "(\d+)(\.(\d+))?') {
        if ($Matches[1] -eq '1') { return [int]$Matches[3] } else { return [int]$Matches[1] }
    }
    return $null
}

Write-Host 'Checking what gitops needs on this machine...'
Write-Host ''
Write-Host 'Required'
if (Has git) { Pass ((git --version) -replace 'git version ', 'git ') } else { Miss 'git: install it from https://git-scm.com' }

# gradlew.bat runs on JAVA_HOME when it's set, otherwise on the java found on PATH.
$javaBin = $null
if ($env:JAVA_HOME -and (Test-Path (Join-Path $env:JAVA_HOME 'bin\java.exe'))) {
    $javaBin = Join-Path $env:JAVA_HOME 'bin\java.exe'; $javaFrom = "JAVA_HOME ($env:JAVA_HOME)"
} elseif (Has java) {
    $javaBin = (Get-Command java).Source; $javaFrom = "PATH ($javaBin)"
}
if (-not $javaBin) {
    Miss 'Java: none found. Install JDK 21 from https://adoptium.net (tick "Set JAVA_HOME")'
} else {
    $major = JavaMajor $javaBin
    if ($null -eq $major) { Note "Java found in $javaFrom, but its version couldn't be read" }
    elseif ($major -ge 21) { Pass "Java $major, from $javaFrom" }
    elseif ($major -ge 17) { Note "Java $major, from ${javaFrom}: enough to start Gradle, which downloads JDK 21 for the build on the first run" }
    else { Miss "Java $major, from ${javaFrom}: Gradle needs Java 17 or newer. Install JDK 21 from https://adoptium.net" }
    if ($env:JAVA_HOME -and (Has java) -and ((Get-Command java).Source -ne $javaBin)) {
        $pathMajor = JavaMajor (Get-Command java).Source
        if ($pathMajor -ne $major) { Note "JAVA_HOME is Java $major but 'java' on PATH is Java $pathMajor; gradlew.bat uses JAVA_HOME" }
    }
}

Write-Host ''
Write-Host 'For the local demo (.\gradlew.bat demo): Node.js or Docker'
$mock = $false
if ((Has node) -and (Has npx)) { Pass "Node.js $(node --version)"; $mock = $true }
if (Has docker) {
    docker info *> $null
    if ($LASTEXITCODE -eq 0) { Pass "Docker $((docker --version) -replace 'Docker version ([^,]+).*', '$1')"; $mock = $true }
    else { Note 'Docker is installed but not running; start Docker Desktop to use it' }
}
if (-not $mock) { Miss 'Neither Node.js nor a running Docker: install Node.js LTS from https://nodejs.org' }
$busy = $false
try { $client = New-Object Net.Sockets.TcpClient; $client.Connect('127.0.0.1', 3000); $busy = $true; $client.Close() } catch { }
if ($busy) { Note 'Port 3000 is in use; stop whatever is on it before .\gradlew.bat demo' } else { Pass 'Port 3000 is free' }

Write-Host ''
Write-Host 'Network (the first build downloads Gradle and the libraries)'
foreach ($url in 'https://services.gradle.org', 'https://repo.maven.apache.org/maven2/', 'https://plugins.gradle.org') {
    try { Invoke-WebRequest -Uri $url -Method Head -TimeoutSec 10 -UseBasicParsing | Out-Null; Pass $url }
    catch {
        # Any HTTP answer means the host is reachable; only a failed connection is a problem.
        if ($_.Exception.Response) { Pass $url } else { Note "Can't reach $url (proxy or firewall?)" }
    }
}

Write-Host ''
if ($script:fail -gt 0) {
    Write-Host "Fix the $($script:fail) item(s) marked 'fix', then run this again."
    exit 1
}
Write-Host 'Ready. Next:'
Write-Host '  .\gradlew.bat demo                         # the whole loop on a local mock'
Write-Host '  .\gradlew.bat plan -Ptarget=prod           # what would change in prod (read-only)'

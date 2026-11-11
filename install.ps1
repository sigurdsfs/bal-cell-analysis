<#
    One-shot setup for the BAL cell segmentation and classification project.

    WHAT IT DOES
      1. Checks everything it needs and REPORTS before touching anything.
      2. Creates the two conda environments and installs the pinned requirements.
      3. Downloads the QuPath Cellpose extension and puts it where QuPath looks.
      4. Makes the directory junctions that publish the scripts to QuPath.
      5. Writes bal_config.local.groovy with the paths it just found.
      6. Pre-downloads the model weights, so the first real run is not a 4 GB wait.
      7. Runs the project's own test harnesses to prove the install works.

    WHAT IT CANNOT DO, and will tell you about instead:
      - Install QuPath, conda or the NVIDIA driver. Those are separate installers with
        their own licence prompts; guessing at a silent install of someone else's software
        is how you end up with a broken machine and no error message.
      - Create the QuPath project, choose the image series, or set the class list. The class
        list belongs to the QuPath WINDOW, not the project, so it cannot be set headlessly
        without QuPath overwriting it on the next save.
      - Supply the slide data.

    SAFE TO RE-RUN. Anything already in place is detected and left alone. Nothing is
    deleted or overwritten without -Force, and an existing bal_config.local.groovy is
    backed up before being replaced.

    USAGE
      .\install.ps1 -DryRun        # report what it would do, change nothing
      .\install.ps1                # do it
      .\install.ps1 -WithCpsam     # also fetch the 1.23 GB Cellpose-SAM weights
      .\install.ps1 -Force         # recreate environments, re-download, replace junctions

    Targets Windows PowerShell 5.1, which is what ships with Windows.
#>

[CmdletBinding()]
param(
    [switch]$DryRun,
    [switch]$Force,
    [switch]$WithCpsam,
    [switch]$SkipEnvs,
    [switch]$SkipExtension,
    [switch]$SkipWeights,
    [switch]$SkipConfig,
    [switch]$SkipVerify,
    [string]$CondaExe,
    [string]$QuPathDir,
    [string]$QuPathUserDir
)

$ErrorActionPreference = 'Stop'
# Windows PowerShell 5.1 defaults to TLS 1.0 for Invoke-WebRequest, which GitHub refuses.
[Net.ServicePointManager]::SecurityProtocol = [Net.SecurityProtocolType]::Tls12

# --- pinned, because "latest" is how a working install stops working -------------------
$EXT_VERSION  = '0.12.1'
$EXT_ZIP_URL  = "https://github.com/BIOP/qupath-extension-cellpose/releases/download/v$EXT_VERSION/qupath-extension-cellpose-$EXT_VERSION.zip"
$EXT_JAR      = "qupath-extension-cellpose-$EXT_VERSION.jar"
$EXT_QC       = 'run-cellpose-qc.py'          # ships inside the same zip
$PY_VERSION   = '3.11'
$ENV_SEG      = 'cellpose_seg'
$ENV_CP3      = 'cellpose3'
$PHIKON       = 'owkin/phikon-v2'

$RepoRoot   = $PSScriptRoot
$ScriptsDir = Join-Path $RepoRoot 'Mathilde Data\qupath_scripts'

# --- output helpers --------------------------------------------------------------------
$script:Problems = @()
$script:Todo     = @()

function Say      { param($m) Write-Host $m }
function SayHead  { param($m) Write-Host ''; Write-Host "== $m" -ForegroundColor Cyan }
function SayOk    { param($m) Write-Host "  [ok]   $m" -ForegroundColor Green }
function SaySkip  { param($m) Write-Host "  [--]   $m" -ForegroundColor DarkGray }
function SayDo    { param($m) Write-Host "  [do]   $m" -ForegroundColor Yellow }
function SayWarn  { param($m) Write-Host "  [warn] $m" -ForegroundColor Yellow }
function SayBad   { param($m) Write-Host "  [STOP] $m" -ForegroundColor Red; $script:Problems += $m }
function AddTodo  { param($m) $script:Todo += $m }

function RunNative {
    # Runs an exe, streams its output, and returns the exit code. PowerShell 5.1 has no
    # && so every caller checks the code itself.
    param([string]$Exe, [string[]]$Arguments, [string]$What)
    Say "  > $Exe $($Arguments -join ' ')"
    if ($DryRun) { SayDo "would run: $What"; return 0 }
    & $Exe @Arguments
    return $LASTEXITCODE
}

function CaptureNative {
    param([string]$Exe, [string[]]$Arguments)
    $out = & $Exe @Arguments
    return ($out | Out-String).Trim()
}

function RunPy {
    # Python code goes through a TEMP FILE, never `python -c "..."`.
    # WHY: PowerShell mangles quotes when it hands an argument to a native exe. A probe of
    # print("torch", torch.__version__, ...) reached python as print(torch, ... with every
    # double quote stripped, and died with "unterminated string literal". A file has no
    # quoting to get wrong.
    param([string]$Py, [string]$Code)
    $f = Join-Path $env:TEMP ("bal-py-" + [guid]::NewGuid().ToString('N') + ".py")
    Set-Content -Path $f -Value $Code -Encoding UTF8
    try {
        $out = & $Py -I $f
        return @{ Code = $LASTEXITCODE; Text = ($out | Out-String).Trim() }
    } finally {
        Remove-Item $f -Force -ErrorAction SilentlyContinue
    }
}

Say ''
Say '================================================================'
Say ' BAL segmentation / classification -- environment setup'
Say '================================================================'
if ($DryRun) { Write-Host ' DRY RUN: nothing will be changed.' -ForegroundColor Magenta }

# =======================================================================================
SayHead 'Step 0 of 7: checking what is already here'
# =======================================================================================

# --- the repository itself -------------------------------------------------------------
$ReqSeg = Join-Path $RepoRoot 'requirements-cellpose_seg.txt'
$ReqCp3 = Join-Path $RepoRoot 'requirements-cellpose3.txt'
foreach ($f in @($ReqSeg, $ReqCp3, (Join-Path $ScriptsDir 'bal_config.groovy'))) {
    if (Test-Path $f) { SayOk "found $(Split-Path $f -Leaf)" }
    else { SayBad "missing $f -- run this script from the repository root" }
}

# --- Windows ---------------------------------------------------------------------------
if ([Environment]::OSVersion.Platform -ne 'Win32NT') {
    SayBad 'This installer is Windows-only; the project paths are Windows paths.'
}

# --- conda -----------------------------------------------------------------------------
if (-not $CondaExe) {
    $cmd = Get-Command conda -ErrorAction SilentlyContinue
    if ($cmd) { $CondaExe = $cmd.Source }
}
if (-not $CondaExe) {
    # conda is usually not on PATH unless the user ran conda init.
    $guesses = @(
        "$env:LOCALAPPDATA\miniconda3\Scripts\conda.exe",
        "$env:LOCALAPPDATA\anaconda3\Scripts\conda.exe",
        "$env:USERPROFILE\miniconda3\Scripts\conda.exe",
        "$env:USERPROFILE\anaconda3\Scripts\conda.exe",
        "$env:USERPROFILE\miniforge3\Scripts\conda.exe",
        "C:\ProgramData\miniconda3\Scripts\conda.exe",
        "C:\ProgramData\anaconda3\Scripts\conda.exe"
    )
    $CondaExe = $guesses | Where-Object { Test-Path $_ } | Select-Object -First 1
}
if ($CondaExe) {
    $cv = CaptureNative $CondaExe @('--version')
    SayOk "conda: $cv  ($CondaExe)"
} else {
    SayBad 'No conda found. Install Miniconda (https://www.anaconda.com/download/success), then re-run. Pass -CondaExe <path> if it is somewhere unusual.'
}

# --- GPU -------------------------------------------------------------------------------
$smi = Get-Command nvidia-smi -ErrorAction SilentlyContinue
if ($smi) {
    $gpu = CaptureNative $smi.Source @('--query-gpu=name,memory.total,driver_version', '--format=csv,noheader')
    SayOk "GPU: $gpu"
} else {
    SayWarn 'nvidia-smi not found. Without an NVIDIA GPU and driver this project runs, but segmentation is impractically slow (hours per region instead of seconds). PyTorch brings its own CUDA runtime, so you need the DRIVER only, not a CUDA toolkit.'
    AddTodo 'Install the NVIDIA driver if this machine has an NVIDIA GPU.'
}

# --- QuPath ----------------------------------------------------------------------------
if (-not $QuPathDir) {
    $QuPathDir = @(
        "$env:LOCALAPPDATA\QuPath-0.7.0",
        "$env:ProgramFiles\QuPath-0.7.0",
        "$env:LOCALAPPDATA\QuPath-0.7",
        "$env:ProgramFiles\QuPath"
    ) | Where-Object { Test-Path $_ } | Select-Object -First 1
}
$QuPathConsole = $null
if ($QuPathDir) {
    $QuPathConsole = Get-ChildItem -Path $QuPathDir -Filter '*console*.exe' -ErrorAction SilentlyContinue |
                     Select-Object -First 1 -ExpandProperty FullName
    if ($QuPathConsole) { SayOk "QuPath: $QuPathDir" }
    else { SayWarn "QuPath found at $QuPathDir but no console executable in it; the verification step will be skipped." }
} else {
    SayBad 'QuPath 0.7.0 not found. Install it from https://qupath.github.io (version 0.7.0 -- the scripts are pinned to its API), then re-run. Pass -QuPathDir <path> if it is somewhere unusual.'
}

# --- QuPath user directory -------------------------------------------------------------
if (-not $QuPathUserDir) { $QuPathUserDir = Join-Path $env:USERPROFILE 'QuPath\v0.7' }
$ExtDir = Join-Path $QuPathUserDir 'extensions'
if (Test-Path $QuPathUserDir) { SayOk "QuPath user directory: $QuPathUserDir" }
else { SayWarn "QuPath user directory $QuPathUserDir does not exist yet; it will be created. If QuPath is configured to use a different one (Edit > Preferences > QuPath user directory), pass -QuPathUserDir <path> or the extension will land in the wrong place." }

# --- disk --------------------------------------------------------------------------------
# Measured on the development machine: 5.5 GB cellpose_seg + 4.9 GB cellpose3 + 3.6 GB of
# model weights. 16 GB is that plus headroom for pip's temporary files.
$drive = (Get-Item $env:USERPROFILE).PSDrive.Name
$free  = [math]::Round((Get-PSDrive $drive).Free / 1GB, 1)
if ($free -lt 16) { SayWarn "only $free GB free on ${drive}: -- this needs about 14 GB (two environments at ~5 GB each plus 3.6 GB of weights)" }
else { SayOk "disk: $free GB free on ${drive}:" }

if ($script:Problems.Count -gt 0) {
    Say ''
    Write-Host '================================================================' -ForegroundColor Red
    Write-Host " Stopping. Nothing was changed. Fix these first:" -ForegroundColor Red
    foreach ($p in $script:Problems) { Write-Host "   - $p" -ForegroundColor Red }
    Write-Host '================================================================' -ForegroundColor Red
    exit 1
}

# =======================================================================================
SayHead "Step 1 of 7: conda environments"
# =======================================================================================
# WHY TWO: cellpose 3 and cellpose 4 are different packages with the same name and cannot
# coexist. cpsam is a cellpose-4 model; cyto2/cyto3/nuclei are cellpose-3 models.

function EnvExists {
    param([string]$Name)
    $list = CaptureNative $CondaExe @('env', 'list')
    foreach ($line in ($list -split "`n")) {
        $first = ($line.Trim() -split '\s+')[0]
        if ($first -eq $Name) { return $true }
    }
    return $false
}

function EnvPython {
    param([string]$Name)
    $p = CaptureNative $CondaExe @('run', '-n', $Name, 'python', '-c', 'import sys; print(sys.executable)')
    if ($LASTEXITCODE -ne 0) { return $null }
    # conda run can prepend its own chatter; the path is the last non-empty line.
    $lines = @($p -split "`n" | Where-Object { $_.Trim() -ne '' })
    if ($lines.Count -eq 0) { return $null }
    return $lines[-1].Trim()
}

function MakeEnv {
    param([string]$Name, [string]$ReqFile)
    $exists = EnvExists $Name
    if ($exists -and $Force) {
        SayDo "removing existing '$Name' (-Force)"
        $rc = RunNative $CondaExe @('env', 'remove', '-n', $Name, '-y') "remove env $Name"
        if ($rc -ne 0) { SayWarn "could not remove '$Name' (exit $rc); continuing with the existing one" }
        else { $exists = $false }
    }
    if ($exists) {
        SaySkip "'$Name' already exists -- keeping it, and making sure the pins match"
    } else {
        SayDo "creating '$Name' with python $PY_VERSION"
        $rc = RunNative $CondaExe @('create', '-n', $Name, "python=$PY_VERSION", '-y') "conda create $Name"
        if ($rc -ne 0) { SayBad "conda create failed for '$Name' (exit $rc)"; return }
    }
    SayDo "installing $(Split-Path $ReqFile -Leaf) into '$Name'"
    # The requirements file carries its own --extra-index-url for the +cu126 wheels.
    $rc = RunNative $CondaExe @('run', '-n', $Name, '--no-capture-output', 'python', '-m', 'pip', 'install', '-r', $ReqFile) "pip install into $Name"
    if ($rc -ne 0) { SayBad "pip install failed for '$Name' (exit $rc) -- see the output above" }
}

if ($SkipEnvs) {
    SaySkip 'skipped (-SkipEnvs)'
} else {
    MakeEnv $ENV_SEG $ReqSeg
    MakeEnv $ENV_CP3 $ReqCp3
}

# Resolve the interpreters, which the config file needs either way.
$PySeg = $null
$PyCp3 = $null
if (-not $DryRun) {
    if (EnvExists $ENV_SEG) { $PySeg = EnvPython $ENV_SEG }
    if (EnvExists $ENV_CP3) { $PyCp3 = EnvPython $ENV_CP3 }
} else {
    if (EnvExists $ENV_SEG) { $PySeg = EnvPython $ENV_SEG }
    if (EnvExists $ENV_CP3) { $PyCp3 = EnvPython $ENV_CP3 }
}
if ($PySeg) { SayOk "cellpose_seg python: $PySeg" } else { SayWarn 'could not resolve the cellpose_seg interpreter' }
if ($PyCp3) { SayOk "cellpose3 python:    $PyCp3" } else { SayWarn 'could not resolve the cellpose3 interpreter' }

# =======================================================================================
SayHead 'Step 2 of 7: is the GPU actually visible to PyTorch?'
# =======================================================================================
# The single most common silent failure: everything installs, nothing uses the GPU.
foreach ($pair in @(@($ENV_SEG, $PySeg), @($ENV_CP3, $PyCp3))) {
    $name = $pair[0]; $py = $pair[1]
    if (-not $py) { SaySkip "$name -- no interpreter to test"; continue }
    if (-not (Test-Path $py)) { SaySkip "$name -- interpreter not on disk yet"; continue }
    $probe = @'
import torch
ok = torch.cuda.is_available()
name = torch.cuda.get_device_name(0) if ok else ""
print("torch", torch.__version__, "cuda", torch.version.cuda, "available", ok, name)
'@
    $r = RunPy $py $probe
    $txt = $r.Text
    if ($txt -match 'available True') { SayOk "$name -- $txt" }
    else { SayWarn "$name -- $txt"; AddTodo "PyTorch in '$name' cannot see a GPU. Fix the NVIDIA driver; reinstalling PyTorch will not help, the CUDA runtime is already inside the wheel." }
}

# =======================================================================================
SayHead 'Step 3 of 7: the QuPath Cellpose extension'
# =======================================================================================
$jarPath = Join-Path $ExtDir $EXT_JAR
$qcPath  = Join-Path $ExtDir $EXT_QC
if ($SkipExtension) {
    SaySkip 'skipped (-SkipExtension)'
} elseif ((Test-Path $jarPath) -and (Test-Path $qcPath) -and (-not $Force)) {
    SaySkip "extension $EXT_VERSION and its QC script are already in $ExtDir"
} else {
    SayDo "downloading qupath-extension-cellpose-$EXT_VERSION.zip"
    if (-not $DryRun) {
        if (-not (Test-Path $ExtDir)) { New-Item -ItemType Directory -Path $ExtDir -Force | Out-Null }
        $tmp = Join-Path $env:TEMP ("bal-ext-" + [guid]::NewGuid().ToString('N'))
        New-Item -ItemType Directory -Path $tmp -Force | Out-Null
        try {
            $zip = Join-Path $tmp 'ext.zip'
            Invoke-WebRequest -Uri $EXT_ZIP_URL -OutFile $zip -UseBasicParsing
            Expand-Archive -Path $zip -DestinationPath $tmp -Force
            # The zip holds the jar and run-cellpose-qc.py. The QC script is optional --
            # without it training still works and QuPath just reports that QC was skipped --
            # but it ships here, so there is no reason not to install it.
            foreach ($leaf in @($EXT_JAR, $EXT_QC)) {
                $src = Get-ChildItem -Path $tmp -Filter $leaf -Recurse | Select-Object -First 1
                if ($src) {
                    Copy-Item $src.FullName (Join-Path $ExtDir $leaf) -Force
                    SayOk "installed $leaf ($([math]::Round($src.Length/1KB)) KB)"
                } else {
                    SayWarn "$leaf was not in the zip -- the release layout may have changed"
                }
            }
        } finally {
            Remove-Item $tmp -Recurse -Force -ErrorAction SilentlyContinue
        }
    }
    AddTodo 'Restart QuPath -- extensions are only loaded at startup.'
}

# =======================================================================================
SayHead 'Step 4 of 7: publish the scripts to QuPath (directory junctions)'
# =======================================================================================
# Mathilde Data\qupath_scripts is the single copy. Junctions mean editing a file there
# changes what QuPath runs, with no copy step to forget. NEVER hard links: editing replaces
# the inode, the menu copy goes stale, and QuPath silently runs old code.

function MakeJunction {
    param([string]$Link, [string]$Target, [string]$Why)
    if (Test-Path $Link) {
        $item = Get-Item $Link -Force
        $existing = $null
        if ($item.PSObject.Properties.Name -contains 'Target') { $existing = @($item.Target)[0] }
        if ($existing -and ((Resolve-Path $existing -ErrorAction SilentlyContinue).Path -eq (Resolve-Path $Target).Path)) {
            SaySkip "$Link -> already correct"
            return
        }
        if (-not $Force) {
            # Fail safe: a real folder here may hold someone's scripts.
            SayWarn "$Link exists and is not a junction to $Target. Left alone. Move it aside, or re-run with -Force."
            AddTodo "Deal with $Link by hand, then re-run -- without it, $Why"
            return
        }
        SayDo "removing $Link (-Force)"
        if (-not $DryRun) { Remove-Item $Link -Recurse -Force }
    }
    SayDo "linking $Link -> $Target"
    if (-not $DryRun) {
        $parent = Split-Path $Link -Parent
        if (-not (Test-Path $parent)) { New-Item -ItemType Directory -Path $parent -Force | Out-Null }
        New-Item -ItemType Junction -Path $Link -Target $Target | Out-Null
        SayOk "linked ($Why)"
    }
}

MakeJunction (Join-Path $QuPathUserDir 'scripts') $ScriptsDir 'the scripts will not appear under Automate > User scripts'
$projDir = Join-Path $RepoRoot 'QuPath Project'
if (Test-Path $projDir) {
    MakeJunction (Join-Path $projDir 'scripts') $ScriptsDir "the project's own scripts folder will be missing"
} else {
    SaySkip "no 'QuPath Project' folder yet -- skipping its scripts junction"
    AddTodo "After creating the QuPath project, re-run this installer (or just .\install.ps1 -SkipEnvs -SkipExtension -SkipWeights) to add the project's scripts junction."
}

# =======================================================================================
SayHead 'Step 5 of 7: bal_config.local.groovy'
# =======================================================================================
# Every machine-specific path in one git-ignored file that overrides bal_config.groovy.
# Only the keys present here are overridden.
$localCfg = Join-Path $ScriptsDir 'bal_config.local.groovy'

# Groovy wants forward slashes. Computed up front as plain variables, because building the
# lines with string + concatenation inside an @(...) array literal does NOT concatenate:
# PowerShell parses 'a' + (expr) + 'b' there as THREE array elements, which silently wrote a
# config file with each path on its own broken line.
function ToFwd { param([string]$p) if ($p) { return ($p -replace '\\', '/') } else { return '' } }
$fSeg   = ToFwd $PySeg
$fCp3   = ToFwd $PyCp3
$fRoot  = ToFwd $RepoRoot
$fClsfy = ToFwd (Join-Path $RepoRoot 'Mathilde Data\bal_classify')
$fGt    = ToFwd (Join-Path $RepoRoot 'Mathilde Data\cellpose_ground_truth')
$stamp  = Get-Date -Format 'yyyy-MM-dd HH:mm'

# A here-string with interpolation: no concatenation to misparse, and the quotes survive
# because nothing hands this to a native exe.
$cfgText = @"
// Written by install.ps1 on $stamp.
// Machine-specific paths. This file is git-ignored and overrides bal_config.groovy;
// only the keys below are replaced. Edit it freely -- re-running the installer backs
// up your copy before writing a new one.
[
    // Lists: the first path that EXISTS wins, so a shared machine can hold several.
    pythonCellpose4 : ["$fSeg"],
    pythonCellpose3 : ["$fCp3"],

    projectRoot     : "$fRoot",
    balClassifyDir  : "$fClsfy",
    groundTruthDir  : "$fGt",
]
"@

if ($SkipConfig) {
    SaySkip 'skipped (-SkipConfig) -- keeping whatever bal_config.local.groovy says now'
} elseif (-not $PySeg -or -not $PyCp3) {
    SayWarn 'not writing the config: one of the interpreters could not be resolved. Fix step 1 and re-run, or write bal_config.local.groovy by hand (INSTALL.md section 6).'
} elseif ($DryRun) {
    SayDo "would write $localCfg"
    Say ''
    # Previewed from the exact text that would be written, line by line.
    foreach ($l in ($cfgText -split "`r?`n")) { Say "         $l" }
} else {
    if (Test-Path $localCfg) {
        $existing = Get-Content $localCfg -Raw
        # Compare without the timestamp line, or every run looks like a change.
        $stripA = ($existing  -split "`r?`n" | Where-Object { $_ -notmatch '^// Written by install\.ps1' }) -join "`n"
        $stripB = ($cfgText   -split "`r?`n" | Where-Object { $_ -notmatch '^// Written by install\.ps1' }) -join "`n"
        if ($stripA.Trim() -eq $stripB.Trim()) {
            SaySkip 'bal_config.local.groovy already says exactly this'
        } else {
            $backup = "$localCfg.bak-" + (Get-Date -Format 'yyyyMMdd-HHmmss')
            Copy-Item $localCfg $backup
            SayWarn "existing config backed up to $(Split-Path $backup -Leaf)"
            Set-Content -Path $localCfg -Value $cfgText -Encoding UTF8 -NoNewline
            SayOk 'wrote bal_config.local.groovy'
        }
    } else {
        Set-Content -Path $localCfg -Value $cfgText -Encoding UTF8 -NoNewline
        SayOk 'wrote bal_config.local.groovy'
    }
}

# =======================================================================================
SayHead 'Step 6 of 7: model weights'
# =======================================================================================
# Not required now -- they download on first use -- but doing it here turns a surprise
# 4 GB wait in the middle of someone's first segmentation into a known cost up front.
if ($SkipWeights) {
    SaySkip 'skipped (-SkipWeights)'
} elseif ($DryRun) {
    SayDo 'would fetch: cyto3 (25 MB), nuclei (25 MB), Phikon-v2 (1.2 GB)'
    if ($WithCpsam) { SayDo 'would also fetch cpsam (1.23 GB)' }
    else { SaySkip 'cpsam (1.23 GB) not fetched -- pass -WithCpsam if you want Cellpose-SAM ready offline' }
} else {
    # cellpose 3 exposes models.model_path(); cellpose 4 renamed it to cache_model_path().
    if ($PyCp3 -and (Test-Path $PyCp3)) {
        SayDo 'cyto3 and nuclei (the default soma and nucleus models)'
        # cellpose 3 calls it model_path(); cellpose 4 renamed it cache_model_path(). Both
        # return the local path and download first if it is missing.
        $r = RunPy $PyCp3 @'
from cellpose import models
print("cyto3:", models.model_path("cyto3"))
print("nuclei:", models.model_path("nuclei"))
'@
        if ($r.Code -eq 0) { SayOk "cyto3 + nuclei ready"; foreach ($l in ($r.Text -split "`r?`n")) { Say "         $l" } }
        else { SayWarn 'could not pre-fetch the cellpose 3 weights; they will download on first use' }
    }
    if ($WithCpsam -and $PySeg -and (Test-Path $PySeg)) {
        SayDo 'cpsam (1.23 GB)'
        $r = RunPy $PySeg @'
from cellpose import models
print("cpsam:", models.cache_model_path("cpsam"))
'@
        if ($r.Code -eq 0) { SayOk "cpsam ready -- $($r.Text)" }
        else { SayWarn 'could not pre-fetch cpsam; it will download on first use' }
    } elseif (-not $WithCpsam) {
        SaySkip 'cpsam (1.23 GB) not fetched -- pass -WithCpsam if you want Cellpose-SAM ready offline'
    }
    if ($PySeg -and (Test-Path $PySeg)) {
        SayDo "Phikon-v2 (1.2 GB, the classifier's frozen vision transformer)"
        $r = RunPy $PySeg "from transformers import AutoImageProcessor, AutoModel`nAutoImageProcessor.from_pretrained('$PHIKON')`nAutoModel.from_pretrained('$PHIKON')`nprint('phikon-v2 cached')"
        if ($r.Code -eq 0) { SayOk 'Phikon-v2 ready' }
        else { SayWarn 'could not pre-fetch Phikon-v2; it will download on the first classification run' }
    }
}

# =======================================================================================
SayHead 'Step 7 of 7: does it work?'
# =======================================================================================
# The project tests itself. These harnesses RUN the code rather than just parsing it --
# Groovy resolves names at runtime, so a script that compiles can still crash on click.
$testsDir = Join-Path $ScriptsDir 'tests'

# The harnesses hold an absolute path to the development machine's checkout rather than
# reading bal_config, so they only pass where the repository actually lives there.
$hardCoded = 'C:/Users/Labsfs/Desktop/Mathilde'
$repoFwd = ToFwd $RepoRoot
$pathMismatch = $false
if ((Test-Path $testsDir) -and ($repoFwd -ne $hardCoded)) {
    $hits = @(Select-String -Path (Join-Path $testsDir '*.groovy') -SimpleMatch $hardCoded -ErrorAction SilentlyContinue)
    if ($hits.Count -gt 0) {
        $pathMismatch = $true
        SayWarn "$($hits.Count) line(s) in tests\ point at '$hardCoded', but this checkout is at '$repoFwd'."
        SayWarn 'The harnesses will fail on paths, not on real problems. This does NOT affect the pipeline itself.'
        AddTodo "Either move the checkout to $hardCoded, or replace that path inside Mathilde Data\qupath_scripts\tests\*.groovy."
    }
}

if ($SkipVerify -or $DryRun) {
    SaySkip 'skipped'
} elseif (-not $QuPathConsole) {
    SaySkip 'no QuPath console executable to run the harnesses with'
} elseif ($pathMismatch) {
    SaySkip 'skipped -- the harnesses would fail on the path mismatch above, which would tell you nothing'
} else {
    foreach ($t in @('compile_only', 'test_gui_all', 'test_region_boundary')) {
        $f = Join-Path $testsDir "$t.groovy"
        if (-not (Test-Path $f)) { SaySkip "$t -- not present"; continue }
        Say "  > $t"
        $out = & $QuPathConsole script $f
        $txt = ($out | Out-String)
        if ($LASTEXITCODE -eq 0) {
            $summary = @($txt -split "`r?`n" | Where-Object { $_ -match 'checks passed|combinations OK|script\(s\) correct|All exist' }) | Select-Object -Last 1
            if (-not $summary) {
                # compile_only prints one "OK <script>" line each and no total, so count them.
                $okCount = @($txt -split "`r?`n" | Where-Object { $_ -match '^OK\s' }).Count
                if ($okCount -gt 0) { $summary = "$okCount scripts OK" } else { $summary = 'passed' }
            }
            SayOk "$t -- $($summary.Trim())"
        } else {
            SayWarn "$t FAILED (exit $LASTEXITCODE). Last lines:"
            @($txt -split "`r?`n" | Where-Object { $_.Trim() -ne '' } | Select-Object -Last 6) | ForEach-Object { Say "         $_" }
            AddTodo "Harness $t failed -- see CLAUDE.md for what it covers."
        }
    }
}

# =======================================================================================
Say ''
Say '================================================================'
if ($DryRun) {
    Write-Host ' DRY RUN finished. Nothing was changed.' -ForegroundColor Magenta
    Write-Host ' Re-run without -DryRun to apply the [do] items above.' -ForegroundColor Magenta
} else {
    Write-Host ' Setup finished.' -ForegroundColor Green
}
Say '================================================================'

Say ''
Say 'STILL TO DO BY HAND -- these cannot be automated:'
Say ''
Say '  1. Restart QuPath, then check Extensions > Cellpose is listed.'
Say '  2. File > Project > Create project, then add your .vsi. When QuPath asks'
Say '     which series, pick the one named like "40x_BF_01" -- NOT "label" and'
Say '     NOT "overview". Those are small thumbnail series in the same file, and'
Say '     measuring them gives answers at the wrong scale.'
Say '  3. Run qupath_setup_classes.groovy once (Automate > User scripts) to add the'
Say '     seven cell-type classes, then SAVE the project with Ctrl+S. The class list'
Say '     belongs to the QuPath window, not the project, and QuPath writes'
Say '     window -> project on save -- so without the save it does not persist.'
Say '  4. Prove the whole chain: open the image, draw a small rectangle, select it,'
Say '     and run Segment cells with cyto3. Outlines in about a minute means the'
Say '     config, junction, extension, conda environment, GPU and weights all work.'

if ($script:Todo.Count -gt 0) {
    Say ''
    Write-Host 'ALSO NOTED:' -ForegroundColor Yellow
    foreach ($t in $script:Todo) { Write-Host "  - $t" -ForegroundColor Yellow }
}
Say ''
Say 'Full detail, and what to do when something fails: INSTALL.md'
Say ''

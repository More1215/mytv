# Compile « MyTV Aide.exe » (aucune installation : le compilateur C# est fourni avec Windows).
# Sans -Url, prend l'adresse du relais de production dans app/build.gradle.kts.
param([string]$Url, [string]$Out = "$PSScriptRoot\MyTV Aide.exe")
$ErrorActionPreference = 'Stop'
if (-not $Url) {
    $gradle = Get-Content "$PSScriptRoot\..\..\app\build.gradle.kts" -Raw
    $Url = [regex]::Match($gradle, 'REMOTE_RELAY_PROD\s*=\s*"([^"]+)"').Groups[1].Value
}
if (-not $Url.EndsWith('/')) { $Url += '/' }
$tmp = Join-Path $env:TEMP 'MyTvAide.cs'
(Get-Content "$PSScriptRoot\MyTvAide.cs" -Raw -Encoding UTF8).Replace('__URL__', $Url) | Set-Content $tmp -Encoding UTF8
# Icône : un écran bleu avec « TV », dessinée ici pour ne dépendre d'aucun fichier.
Add-Type -AssemblyName System.Drawing
$ico = Join-Path $env:TEMP 'MyTvAide.ico'
$bmp = New-Object System.Drawing.Bitmap 64, 64
$g = [System.Drawing.Graphics]::FromImage($bmp)
$g.SmoothingMode = 'AntiAlias'; $g.TextRenderingHint = 'AntiAlias'
$g.FillRectangle((New-Object System.Drawing.SolidBrush ([System.Drawing.Color]::FromArgb(47,128,237))), 4, 8, 56, 40)
$g.FillRectangle([System.Drawing.Brushes]::White, 26, 50, 12, 6)
$g.DrawString('TV', (New-Object System.Drawing.Font 'Segoe UI', 18, ([System.Drawing.FontStyle]::Bold)), [System.Drawing.Brushes]::White, 13, 11)
$icon = [System.Drawing.Icon]::FromHandle($bmp.GetHicon())
$fs = [System.IO.File]::Create($ico); $icon.Save($fs); $fs.Close()
& "$env:WINDIR\Microsoft.NET\Framework64\v4.0.30319\csc.exe" /nologo /target:winexe /codepage:65001 "/win32icon:$ico" "/out:$Out" /r:System.Windows.Forms.dll $tmp
if ($LASTEXITCODE -ne 0) { throw "Compilation échouée" }
Write-Output "OK : $Out -> $Url"

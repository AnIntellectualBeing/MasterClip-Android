# Reproducible spoken AAC fixture. Android 16 exposes its negative AAC priming timestamp.
$ErrorActionPreference = 'Stop'
$captionRoot = Split-Path $PSScriptRoot -Parent
$captionBuild = Join-Path $captionRoot 'build'
New-Item -ItemType Directory -Path $captionBuild -Force | Out-Null
Add-Type -AssemblyName System.Speech
$captionVoice = New-Object System.Speech.Synthesis.SpeechSynthesizer
try {
    $captionVoice.SetOutputToWaveFile((Join-Path $captionBuild 'caption-smoke.wav'))
    $captionVoice.Speak('Welcome to MasterClip. These captions should follow every word. We are testing speech recognition on this phone. The final video should show clear captions.')
} finally { $captionVoice.Dispose() }
& ffmpeg -hide_banner -loglevel error -y -f lavfi -i color=c=0x15304a:s=640x360:r=30:d=12 -i (Join-Path $captionBuild 'caption-smoke.wav') -t 12 -c:v libx264 -pix_fmt yuv420p -c:a aac (Join-Path $captionBuild 'caption-smoke.mp4')
if ($LASTEXITCODE -ne 0) { throw 'Could not create caption fixture' }
Write-Host (Join-Path $captionBuild 'caption-smoke.mp4')

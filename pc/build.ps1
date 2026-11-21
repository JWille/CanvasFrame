# Builds CanvasFrame Link (tray app + service, one exe) into pc\dist.
#   - build\app.ico: the CanvasFrame monogram, drawn here (no image files in the repo)
#   - build\dashboard.css: the dashboard's stylesheet (launcher/assets/dashboard.html), so the window looks like the web UI
# Needs the .NET SDK (any recent one; the app itself targets .NET Framework 4.8, which is part of Windows).
# Run pc\dist\CanvasFrameLink.exe once: it installs itself for this Windows user and starts in the tray.
$ErrorActionPreference = 'Stop'
$root = $PSScriptRoot
$build = Join-Path $root 'build'
New-Item -ItemType Directory -Force $build | Out-Null

# --- stylesheet from the dashboard
$dash = Get-Content -Raw -Encoding utf8 (Join-Path $root '..\launcher\assets\dashboard.html')
$m = [regex]::Match($dash, '<style>([\s\S]*?)</style>')
if (-not $m.Success) { throw 'No <style> block in dashboard.html' }
[IO.File]::WriteAllText((Join-Path $build 'dashboard.css'), $m.Groups[1].Value, (New-Object Text.UTF8Encoding $false))

# --- icon: rounded square in the dashboard's dark brown with a gold "CF", PNG frames in an .ico
Add-Type -AssemblyName System.Drawing
$sizes = 16, 20, 24, 32, 40, 48, 64, 256
$frames = foreach ($s in $sizes) {
  $bmp = New-Object Drawing.Bitmap $s, $s
  $g = [Drawing.Graphics]::FromImage($bmp)
  $g.SmoothingMode = 'AntiAlias'; $g.TextRenderingHint = 'AntiAliasGridFit'
  $r = [Math]::Max(3, [int]($s * 0.22)); $d = 2 * $r
  $p = New-Object Drawing.Drawing2D.GraphicsPath
  $p.AddArc(0, 0, $d, $d, 180, 90); $p.AddArc($s - $d - 1, 0, $d, $d, 270, 90)
  $p.AddArc($s - $d - 1, $s - $d - 1, $d, $d, 0, 90); $p.AddArc(0, $s - $d - 1, $d, $d, 90, 90); $p.CloseFigure()
  $g.FillPath((New-Object Drawing.SolidBrush ([Drawing.Color]::FromArgb(255, 0x2a, 0x24, 0x1f))), $p)
  $g.DrawPath((New-Object Drawing.Pen ([Drawing.Color]::FromArgb(255, 0xe8, 0xc9, 0x9a), [Math]::Max(1, $s / 32))), $p)
  $font = New-Object Drawing.Font 'Segoe UI', ([float]($s * 0.42)), ([Drawing.FontStyle]::Bold), ([Drawing.GraphicsUnit]::Pixel)
  $sf = New-Object Drawing.StringFormat; $sf.Alignment = 'Center'; $sf.LineAlignment = 'Center'
  $g.DrawString('CF', $font, (New-Object Drawing.SolidBrush ([Drawing.Color]::FromArgb(255, 0xe8, 0xc9, 0x9a))), (New-Object Drawing.RectangleF 0, ([float]($s * 0.03)), $s, $s), $sf)
  $g.Dispose()
  $ms = New-Object IO.MemoryStream; $bmp.Save($ms, [Drawing.Imaging.ImageFormat]::Png); $bmp.Dispose()
  , @($s, $ms.ToArray())
}
$ico = New-Object IO.MemoryStream; $w = New-Object IO.BinaryWriter $ico
$w.Write([uint16]0); $w.Write([uint16]1); $w.Write([uint16]$frames.Count)
$offset = 6 + 16 * $frames.Count
foreach ($f in $frames) {
  $s = $f[0]; $len = $f[1].Length
  $w.Write([byte]($s % 256)); $w.Write([byte]($s % 256)); $w.Write([byte]0); $w.Write([byte]0)
  $w.Write([uint16]1); $w.Write([uint16]32); $w.Write([uint32]$len); $w.Write([uint32]$offset); $offset += $len
}
foreach ($f in $frames) { $w.Write($f[1]) }
[IO.File]::WriteAllBytes((Join-Path $build 'app.ico'), $ico.ToArray())

# --- compile
$dist = Join-Path $root 'dist'
& dotnet build (Join-Path $root 'CanvasFrameLink.csproj') -c Release -o $dist --nologo -v q
if ($LASTEXITCODE) { throw 'dotnet build failed' }
Write-Host "Built $dist\CanvasFrameLink.exe"

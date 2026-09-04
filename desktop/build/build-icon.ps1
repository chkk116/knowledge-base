# build-icon.ps1 - Generate knowledge base desktop icon.
# Design: 256x256 rounded square, deep blue-purple vertical gradient,
#         three abstract book blocks + shelf line. No KB letters, no glass.
# Pure ASCII / no BOM. Runs on PowerShell 5.1+.
# Usage: powershell -ExecutionPolicy Bypass -File build\build-icon.ps1
Add-Type -AssemblyName System.Drawing

$outDir = Join-Path $PSScriptRoot 'build'
if (-not (Test-Path $outDir)) { New-Item -ItemType Directory -Path $outDir -Force | Out-Null }

$size = 256
$bmp = New-Object System.Drawing.Bitmap $size, $size
$g = [System.Drawing.Graphics]::FromImage($bmp)
$g.SmoothingMode = [System.Drawing.Drawing2D.SmoothingMode]::AntiAlias
$g.InterpolationMode = [System.Drawing.Drawing2D.InterpolationMode]::HighQualityBicubic
$g.PixelOffsetMode = [System.Drawing.Drawing2D.PixelOffsetMode]::HighQuality

# Rounded rectangle background (gradient)
$radius = 48
$rect = New-Object System.Drawing.Rectangle 0, 0, $size, $size
$gp = New-Object System.Drawing.Drawing2D.GraphicsPath
$d = $radius * 2
$gp.AddArc($rect.X, $rect.Y, $d, $d, 180, 90)
$gp.AddArc($rect.Right - $d, $rect.Y, $d, $d, 270, 90)
$gp.AddArc($rect.Right - $d, $rect.Bottom - $d, $d, $d, 0, 90)
$gp.AddArc($rect.X, $rect.Bottom - $d, $d, $d, 90, 90)
$gp.CloseFigure()
$grad = New-Object System.Drawing.Drawing2D.LinearGradientBrush(
    $rect,
    [System.Drawing.Color]::FromArgb(255, 30, 27, 75),
    [System.Drawing.Color]::FromArgb(255, 67, 56, 202),
    [System.Drawing.Drawing2D.LinearGradientMode]::Vertical
)
$g.FillPath($grad, $gp)
$gp.Dispose()
$grad.Dispose()

# Three books (knowledge shelf concept)
$colors = @(
    [System.Drawing.Color]::FromArgb(255, 129, 140, 248),
    [System.Drawing.Color]::FromArgb(255, 165, 180, 252),
    [System.Drawing.Color]::FromArgb(255, 199, 210, 254)
)
$heights = @(132, 148, 118)
$bookW = 36
$gap = 16
$totalW = $bookW * 3 + $gap * 2
$startX = [int](($size - $totalW) / 2)
$baseline = 200
$spineColor = [System.Drawing.Color]::FromArgb(255, 224, 231, 255)
$spineW = 2
for ($i = 0; $i -lt 3; $i++) {
    $bx = $startX + $i * ($bookW + $gap)
    $bh = $heights[$i]
    $by = $baseline - $bh
    $brush = New-Object System.Drawing.SolidBrush $colors[$i]
    $g.FillRectangle($brush, $bx, $by, $bookW, $bh)
    $brush.Dispose()
    $pen = New-Object System.Drawing.Pen $spineColor, $spineW
    $g.DrawLine($pen, ($bx + $bookW - 3), ($by + 6), ($bx + $bookW - 3), ($by + $bh - 6))
    $pen.Dispose()
}

# Shelf line under books
$shelfPen = New-Object System.Drawing.Pen ([System.Drawing.Color]::FromArgb(180, 224, 231, 255)), 3
$g.DrawLine($shelfPen, ($startX - 8), ($baseline + 4), ($startX + $totalW + 8), ($baseline + 4))
$shelfPen.Dispose()

$g.Dispose()

# Save PNG
$pngPath = Join-Path $outDir 'icon.png'
$bmp.Save($pngPath, [System.Drawing.Imaging.ImageFormat]::Png)

# Save ICO (PNG-in-ICO, single 256x256, Vista+ supported)
$ms = New-Object System.IO.MemoryStream
$bmp.Save($ms, [System.Drawing.Imaging.ImageFormat]::Png)
$pngBytes = $ms.ToArray()
$ms.Dispose()

$icoPath = Join-Path $outDir 'icon.ico'
$fs = [System.IO.File]::Create($icoPath)
$bw = New-Object System.IO.BinaryWriter $fs
$bw.Write([UInt16]0)            # reserved
$bw.Write([UInt16]1)            # type icon
$bw.Write([UInt16]1)            # count
$bw.Write([byte]0)              # width (0 = 256)
$bw.Write([byte]0)              # height (0 = 256)
$bw.Write([byte]0)              # colors
$bw.Write([byte]0)              # reserved
$bw.Write([UInt16]1)            # planes
$bw.Write([UInt16]32)           # bit count
$bw.Write([UInt32]$pngBytes.Length)
$bw.Write([UInt32]22)           # offset (6 header + 16 entry)
$bw.Write($pngBytes)
$bw.Flush()
$bw.Close()
$fs.Close()
$bmp.Dispose()

$pngLen = (Get-Item $pngPath).Length
$icoLen = (Get-Item $icoPath).Length
Write-Host ("[kb-desktop-icon] Generated {0} ({1} bytes) and {2} ({3} bytes)" -f $pngPath, $pngLen, $icoPath, $icoLen)
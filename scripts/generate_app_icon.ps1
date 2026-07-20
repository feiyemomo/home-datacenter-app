# v1.6.4 rev4: regenerate app icon from D:\Projects\Android\home.png
# with tighter content framing so the house graphic appears larger
# on the launcher. The previous generation used the source image
# as-is which had ~15% transparent padding; this script auto-crops
# the transparent border, then composes the cropped image onto a
# padded canvas at 92% content coverage (8% padding) — visually
# "larger" without violating the adaptive icon safe zone (which
# recommends 66% visible area = ~34% padding for circular masks).
#
# Generates 10 PNGs (square + round) across 5 mipmap densities:
#   mdpi  :  48x48
#   hdpi  :  72x72
#   xhdpi :  96x96
#   xxhdpi: 144x144
#   xxxhdpi: 192x192

param(
    [string]$Source = "D:\Projects\Android\home.png",
    [string]$ResDir = "d:\Projects\Android\app\src\main\res"
)

Add-Type -AssemblyName System.Drawing

# 1. Load source and auto-crop transparent border.
Write-Host "Loading source: $Source"
$src = [System.Drawing.Image]::FromFile($Source)
Write-Host "Source dimensions: $($src.Width) x $($src.Height)"

# Lock bits to find non-transparent bounding box.
$bmp = New-Object System.Drawing.Bitmap($src)
$rect = New-Object System.Drawing.Rectangle(0, 0, $bmp.Width, $bmp.Height)
$data = $bmp.LockBits($rect, [System.Drawing.Imaging.ImageLockMode]::ReadOnly, [System.Drawing.Imaging.PixelFormat]::Format32bppArgb)
$stride = $data.Stride
$bytes = New-Object byte[] ($stride * $bmp.Height)
[System.Runtime.InteropServices.Marshal]::Copy($data.Scan0, $bytes, 0, $bytes.Length)
$bmp.UnlockBits($data)

$minX = $bmp.Width; $minY = $bmp.Height; $maxX = 0; $maxY = 0
for ($y = 0; $y -lt $bmp.Height; $y++) {
    for ($x = 0; $x -lt $bmp.Width; $x++) {
        $offset = $y * $stride + $x * 4
        $alpha = $bytes[$offset + 3]
        if ($alpha -gt 16) {  # threshold for "visible"
            if ($x -lt $minX) { $minX = $x }
            if ($x -gt $maxX) { $maxX = $x }
            if ($y -lt $minY) { $minY = $y }
            if ($y -gt $maxY) { $maxY = $y }
        }
    }
}
Write-Host "Content bbox: ($minX,$minY) -> ($maxX,$maxY)"

$contentW = $maxX - $minX + 1
$contentH = $maxY - $minY + 1
Write-Host "Content size: $contentW x $contentH"

# 2. Crop source to content bbox.
$cropped = New-Object System.Drawing.Bitmap($contentW, $contentH, [System.Drawing.Imaging.PixelFormat]::Format32bppArgb)
$g = [System.Drawing.Graphics]::FromImage($cropped)
$g.DrawImage($bmp, (New-Object System.Drawing.Rectangle(0, 0, $contentW, $contentH)),
    $minX, $minY, $contentW, $contentH, [System.Drawing.GraphicsUnit]::Pixel)
$g.Dispose()
$bmp.Dispose()
$src.Dispose()

# 3. Generate each mipmap density.
$densities = @{
    "mipmap-mdpi"    = 48
    "mipmap-hdpi"    = 72
    "mipmap-xhdpi"   = 96
    "mipmap-xxhdpi"  = 144
    "mipmap-xxxhdpi" = 192
}

foreach ($entry in $densities.GetEnumerator()) {
    $folder = $entry.Key
    $size = $entry.Value
    $dirPath = Join-Path $ResDir $folder
    if (-not (Test-Path $dirPath)) { New-Item -ItemType Directory -Path $dirPath -Force | Out-Null }

    # Target canvas: $size x $size with 8% padding (icon content fills 92%).
    # This is tighter than the typical 15% adaptive icon padding,
    # making the graphic visually larger on the launcher.
    $padding = [int]($size * 0.08)
    $contentSize = $size - 2 * $padding

    # Square icon.
    $squarePath = Join-Path $dirPath "ic_launcher.png"
    $square = New-Object System.Drawing.Bitmap($size, $size, [System.Drawing.Imaging.PixelFormat]::Format32bppArgb)
    $gs = [System.Drawing.Graphics]::FromImage($square)
    $gs.InterpolationMode = [System.Drawing.Drawing2D.InterpolationMode]::HighQualityBicubic
    $gs.PixelOffsetMode = [System.Drawing.Drawing2D.PixelOffsetMode]::HighQuality
    $gs.SmoothingMode = [System.Drawing.Drawing2D.SmoothingMode]::HighQuality
    # Transparent background.
    $gs.Clear([System.Drawing.Color]::Transparent)
    # Draw content scaled to contentSize at padding offset.
    $gs.DrawImage($cropped, (New-Object System.Drawing.Rectangle($padding, $padding, $contentSize, $contentSize)),
        0, 0, $cropped.Width, $cropped.Height, [System.Drawing.GraphicsUnit]::Pixel)
    $gs.Dispose()
    $square.Save($squarePath, [System.Drawing.Imaging.ImageFormat]::Png)
    $square.Dispose()
    Write-Host "Saved: $squarePath ($size x $size)"

    # Round icon: same image, but with circular mask applied.
    $roundPath = Join-Path $dirPath "ic_launcher_round.png"
    $round = New-Object System.Drawing.Bitmap($size, $size, [System.Drawing.Imaging.PixelFormat]::Format32bppArgb)
    $gr = [System.Drawing.Graphics]::FromImage($round)
    $gr.InterpolationMode = [System.Drawing.Drawing2D.InterpolationMode]::HighQualityBicubic
    $gr.PixelOffsetMode = [System.Drawing.Drawing2D.PixelOffsetMode]::HighQuality
    $gr.SmoothingMode = [System.Drawing.Drawing2D.SmoothingMode]::HighQuality
    $gr.Clear([System.Drawing.Color]::Transparent)
    # Apply circular clip path.
    $clipPath = New-Object System.Drawing.Drawing2D.GraphicsPath
    $clipPath.AddEllipse(0, 0, $size, $size)
    $gr.SetClip($clipPath)
    $gr.DrawImage($cropped, (New-Object System.Drawing.Rectangle($padding, $padding, $contentSize, $contentSize)),
        0, 0, $cropped.Width, $cropped.Height, [System.Drawing.GraphicsUnit]::Pixel)
    $gr.Dispose()
    $round.Save($roundPath, [System.Drawing.Imaging.ImageFormat]::Png)
    $round.Dispose()
    Write-Host "Saved: $roundPath ($size x $size, circular mask)"
}

$cropped.Dispose()
Write-Host "`nAll 10 icon PNGs regenerated successfully."

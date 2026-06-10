# Phase-0 spike helper: crop a region from a screenshot and rescale it.
# Usage: .\crop.ps1 -In <src.png> -Out <dst.png> -X 950 -Y 350 -W 920 -H 1500 [-Scale 1.5]
# Coordinates are in SOURCE pixels. Scale > 1 upscales (HighQualityBicubic - the
# JDK/Skia-equivalent resampling class the production pipeline will use; true
# Lanczos is unavailable in both, see issue #433).
param(
    [Parameter(Mandatory)] [string]$In,
    [Parameter(Mandatory)] [string]$Out,
    [Parameter(Mandatory)] [int]$X,
    [Parameter(Mandatory)] [int]$Y,
    [Parameter(Mandatory)] [int]$W,
    [Parameter(Mandatory)] [int]$H,
    [double]$Scale = 1.0
)
Add-Type -AssemblyName System.Drawing
$src = [System.Drawing.Image]::FromFile((Resolve-Path $In))
try {
    $cw = [Math]::Min($W, $src.Width - $X)
    $ch = [Math]::Min($H, $src.Height - $Y)
    $tw = [int]([Math]::Round($cw * $Scale))
    $th = [int]([Math]::Round($ch * $Scale))
    $dst = New-Object System.Drawing.Bitmap($tw, $th)
    try {
        $g = [System.Drawing.Graphics]::FromImage($dst)
        $g.InterpolationMode = [System.Drawing.Drawing2D.InterpolationMode]::HighQualityBicubic
        $g.PixelOffsetMode = [System.Drawing.Drawing2D.PixelOffsetMode]::HighQuality
        $srcRect = New-Object System.Drawing.Rectangle($X, $Y, $cw, $ch)
        $dstRect = New-Object System.Drawing.Rectangle(0, 0, $tw, $th)
        $g.DrawImage($src, $dstRect, $srcRect, [System.Drawing.GraphicsUnit]::Pixel)
        $g.Dispose()
        $dst.Save($Out, [System.Drawing.Imaging.ImageFormat]::Png)
        "$Out <- $In [$X,$Y ${cw}x$ch] x$Scale -> ${tw}x$th"
    } finally { $dst.Dispose() }
} finally { $src.Dispose() }

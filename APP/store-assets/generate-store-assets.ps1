$ErrorActionPreference = "Stop"

Add-Type -AssemblyName System.Drawing

$outputDir = $PSScriptRoot
$iconPath = Join-Path $outputDir "ridescope-store-icon-512.png"
$featurePath = Join-Path $outputDir "ridescope-feature-graphic-1024x500.png"

function New-Pen {
    param(
        [string]$Color,
        [float]$Width
    )

    $pen = [System.Drawing.Pen]::new([System.Drawing.ColorTranslator]::FromHtml($Color), $Width)
    $pen.StartCap = [System.Drawing.Drawing2D.LineCap]::Round
    $pen.EndCap = [System.Drawing.Drawing2D.LineCap]::Round
    $pen.LineJoin = [System.Drawing.Drawing2D.LineJoin]::Round
    $pen
}

function Draw-RideScopeMark {
    param(
        [System.Drawing.Graphics]$Graphics,
        [float]$CenterX,
        [float]$CenterY,
        [float]$Scale
    )

    $white = "#F8FAFC"
    $blue = "#3AAEFF"
    $green = "#6CDB48"
    $yellow = "#F2E84A"

    $dimPen = New-Pen "#55F8FAFC" (5.2 * $Scale)
    $bluePen = New-Pen $blue (6.0 * $Scale)
    $greenPen = New-Pen $green (6.0 * $Scale)
    $yellowPen = New-Pen $yellow (6.0 * $Scale)

    $gauge = [System.Drawing.RectangleF]::new(
        $CenterX - (41 * $Scale),
        $CenterY - (44 * $Scale),
        82 * $Scale,
        82 * $Scale
    )
    $Graphics.DrawArc($dimPen, $gauge, 205, 130)
    $Graphics.DrawArc($bluePen, $gauge, 205, 42)
    $Graphics.DrawArc($greenPen, $gauge, 247, 45)
    $Graphics.DrawArc($yellowPen, $gauge, 292, 43)

    $state = $Graphics.Save()
    $Graphics.TranslateTransform($CenterX, $CenterY + (3 * $Scale))
    $Graphics.RotateTransform(18)
    $Graphics.TranslateTransform(-$CenterX, -($CenterY + (3 * $Scale)))

    $bikePen = New-Pen $white (4.0 * $Scale)
    $wheelPen = New-Pen $white (3.6 * $Scale)
    $headBrush = [System.Drawing.SolidBrush]::new([System.Drawing.ColorTranslator]::FromHtml($white))

    $leftWheel = [System.Drawing.RectangleF]::new($CenterX - (23 * $Scale), $CenterY + (11 * $Scale), 14 * $Scale, 14 * $Scale)
    $rightWheel = [System.Drawing.RectangleF]::new($CenterX + (13 * $Scale), $CenterY + (11 * $Scale), 14 * $Scale, 14 * $Scale)
    $Graphics.DrawEllipse($wheelPen, $leftWheel)
    $Graphics.DrawEllipse($wheelPen, $rightWheel)

    $points = @(
        [System.Drawing.PointF]::new($CenterX - (16 * $Scale), $CenterY + (18 * $Scale)),
        [System.Drawing.PointF]::new($CenterX - (5 * $Scale), $CenterY + (2 * $Scale)),
        [System.Drawing.PointF]::new($CenterX + (12 * $Scale), $CenterY + (2 * $Scale)),
        [System.Drawing.PointF]::new($CenterX + (20 * $Scale), $CenterY + (18 * $Scale))
    )
    $Graphics.DrawLines($bikePen, $points)

    $Graphics.DrawLine($bikePen, $CenterX - (5 * $Scale), $CenterY + (2 * $Scale), $CenterX + (4 * $Scale), $CenterY - (10 * $Scale))
    $Graphics.DrawLine($bikePen, $CenterX + (4 * $Scale), $CenterY - (10 * $Scale), $CenterX + (17 * $Scale), $CenterY - (9 * $Scale))
    $Graphics.DrawLine($bikePen, $CenterX - (4 * $Scale), $CenterY + (2 * $Scale), $CenterX - (12 * $Scale), $CenterY - (5 * $Scale))
    $Graphics.DrawLine($bikePen, $CenterX + (6 * $Scale), $CenterY - (8 * $Scale), $CenterX + (15 * $Scale), $CenterY - (14 * $Scale))
    $Graphics.DrawLine($bikePen, $CenterX + (12 * $Scale), $CenterY + (2 * $Scale), $CenterX + (18 * $Scale), $CenterY - (5 * $Scale))
    $Graphics.FillEllipse(
        $headBrush,
        $CenterX - (1 * $Scale),
        $CenterY - (23 * $Scale),
        10 * $Scale,
        10 * $Scale
    )
    $Graphics.DrawLine($bikePen, $CenterX + (2 * $Scale), $CenterY - (18 * $Scale), $CenterX - (4 * $Scale), $CenterY - (10 * $Scale))

    $Graphics.Restore($state)

    $dimPen.Dispose()
    $bluePen.Dispose()
    $greenPen.Dispose()
    $yellowPen.Dispose()
    $bikePen.Dispose()
    $wheelPen.Dispose()
    $headBrush.Dispose()
}

function Save-StoreIcon {
    $bitmap = [System.Drawing.Bitmap]::new(512, 512, [System.Drawing.Imaging.PixelFormat]::Format32bppArgb)
    $graphics = [System.Drawing.Graphics]::FromImage($bitmap)
    $graphics.SmoothingMode = [System.Drawing.Drawing2D.SmoothingMode]::AntiAlias
    $graphics.Clear([System.Drawing.Color]::Transparent)

    $rect = [System.Drawing.Rectangle]::new(0, 0, 512, 512)
    $bg = [System.Drawing.Drawing2D.LinearGradientBrush]::new(
        $rect,
        [System.Drawing.ColorTranslator]::FromHtml("#1A212A"),
        [System.Drawing.ColorTranslator]::FromHtml("#040608"),
        135
    )
    $graphics.FillRectangle($bg, $rect)

    $terrainBrush = [System.Drawing.SolidBrush]::new([System.Drawing.ColorTranslator]::FromHtml("#0F1620"))
    $terrain = [System.Drawing.Drawing2D.GraphicsPath]::new()
    $terrain.StartFigure()
    $terrain.AddBezier(0, 372, 115, 290, 252, 274, 512, 348)
    $terrain.AddLine(512, 512, 0, 512)
    $terrain.CloseFigure()
    $graphics.FillPath($terrainBrush, $terrain)

    $lineBlue = New-Pen "#443AAEFF" 10
    $lineYellow = New-Pen "#33F2E84A" 8
    $graphics.DrawBezier($lineBlue, 70, 400, 150, 310, 250, 270, 380, 280)
    $graphics.DrawBezier($lineYellow, 95, 428, 190, 320, 310, 300, 420, 326)

    Draw-RideScopeMark -Graphics $graphics -CenterX 256 -CenterY 272 -Scale 4.0

    $bitmap.Save($iconPath, [System.Drawing.Imaging.ImageFormat]::Png)

    $lineBlue.Dispose()
    $lineYellow.Dispose()
    $terrain.Dispose()
    $terrainBrush.Dispose()
    $bg.Dispose()
    $graphics.Dispose()
    $bitmap.Dispose()
}

function Save-FeatureGraphic {
    $bitmap = [System.Drawing.Bitmap]::new(1024, 500, [System.Drawing.Imaging.PixelFormat]::Format24bppRgb)
    $graphics = [System.Drawing.Graphics]::FromImage($bitmap)
    $graphics.SmoothingMode = [System.Drawing.Drawing2D.SmoothingMode]::AntiAlias

    $rect = [System.Drawing.Rectangle]::new(0, 0, 1024, 500)
    $bg = [System.Drawing.Drawing2D.LinearGradientBrush]::new(
        $rect,
        [System.Drawing.ColorTranslator]::FromHtml("#111820"),
        [System.Drawing.ColorTranslator]::FromHtml("#05070A"),
        18
    )
    $graphics.FillRectangle($bg, $rect)

    $roadBrush = [System.Drawing.SolidBrush]::new([System.Drawing.ColorTranslator]::FromHtml("#0D141C"))
    $road = [System.Drawing.Drawing2D.GraphicsPath]::new()
    $road.StartFigure()
    $road.AddBezier(-20, 378, 190, 260, 460, 285, 1044, 220)
    $road.AddLine(1044, 500, -20, 500)
    $road.CloseFigure()
    $graphics.FillPath($roadBrush, $road)

    $bluePen = New-Pen "#553AAEFF" 7
    $yellowPen = New-Pen "#44F2E84A" 6
    $whitePen = New-Pen "#22F8FAFC" 5
    $graphics.DrawBezier($bluePen, 30, 380, 230, 275, 438, 260, 702, 262)
    $graphics.DrawBezier($yellowPen, 74, 415, 285, 310, 555, 315, 910, 266)
    $graphics.DrawBezier($whitePen, 620, 218, 742, 170, 877, 166, 1000, 124)

    Draw-RideScopeMark -Graphics $graphics -CenterX 250 -CenterY 270 -Scale 2.95

    $titleFont = [System.Drawing.Font]::new("Segoe UI Semibold", 74, [System.Drawing.FontStyle]::Regular, [System.Drawing.GraphicsUnit]::Pixel)
    $subtitleFont = [System.Drawing.Font]::new("Segoe UI", 30, [System.Drawing.FontStyle]::Regular, [System.Drawing.GraphicsUnit]::Pixel)
    $whiteBrush = [System.Drawing.SolidBrush]::new([System.Drawing.ColorTranslator]::FromHtml("#F8FAFC"))
    $mutedBrush = [System.Drawing.SolidBrush]::new([System.Drawing.ColorTranslator]::FromHtml("#B8C3CF"))
    $greenBrush = [System.Drawing.SolidBrush]::new([System.Drawing.ColorTranslator]::FromHtml("#6CDB48"))

    $graphics.DrawString("RideScope", $titleFont, $whiteBrush, 482, 168)
    $graphics.DrawString("Telemetria moto via BLE", $subtitleFont, $mutedBrush, 486, 250)
    $graphics.FillRectangle($greenBrush, 486, 310, 150, 6)

    $bitmap.Save($featurePath, [System.Drawing.Imaging.ImageFormat]::Png)

    $greenBrush.Dispose()
    $mutedBrush.Dispose()
    $whiteBrush.Dispose()
    $subtitleFont.Dispose()
    $titleFont.Dispose()
    $whitePen.Dispose()
    $yellowPen.Dispose()
    $bluePen.Dispose()
    $road.Dispose()
    $roadBrush.Dispose()
    $bg.Dispose()
    $graphics.Dispose()
    $bitmap.Dispose()
}

Save-StoreIcon
Save-FeatureGraphic

Write-Host "Creato: $iconPath"
Write-Host "Creato: $featurePath"

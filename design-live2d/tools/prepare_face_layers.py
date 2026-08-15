from pathlib import Path

import numpy as np
from PIL import Image, ImageChops, ImageDraw, ImageFilter


ROOT = Path(__file__).resolve().parents[1]
SOURCE = ROOT / "source" / "paper-messenger-neutral-safe.png"
MOUTH_OPEN_SOURCE = ROOT / "source" / "paper-messenger-mouth-open.png"
MASKS = ROOT / "masks"
LAYERS = ROOT / "layers"
REFERENCE_SIZE = 1254


def scaled_box(box: tuple[int, int, int, int], size: tuple[int, int]) -> tuple[int, int, int, int]:
    scale_x = size[0] / REFERENCE_SIZE
    scale_y = size[1] / REFERENCE_SIZE
    return tuple(round(value * (scale_x if index % 2 == 0 else scale_y)) for index, value in enumerate(box))


def ellipse_mask(size: tuple[int, int], box: tuple[int, int, int, int], feather: int = 4) -> Image.Image:
    scale = 4
    mask = Image.new("L", (size[0] * scale, size[1] * scale), 0)
    draw = ImageDraw.Draw(mask)
    scaled = scaled_box(box, size)
    draw.ellipse(tuple(value * scale for value in scaled), fill=255)
    return mask.resize(size, Image.Resampling.LANCZOS)


def polygon_mask(size: tuple[int, int], points: list[tuple[int, int]]) -> Image.Image:
    scale = 4
    mask = Image.new("L", (size[0] * scale, size[1] * scale), 0)
    scale_x = size[0] / REFERENCE_SIZE
    scale_y = size[1] / REFERENCE_SIZE
    scaled_points = [(round(x * scale_x * scale), round(y * scale_y * scale)) for x, y in points]
    ImageDraw.Draw(mask).polygon(scaled_points, fill=255)
    return mask.resize(size, Image.Resampling.LANCZOS)


def color_mask(
    source: Image.Image,
    box: tuple[int, int, int, int],
    selector,
    dilation: int = 5,
    blur: float = 1.2,
) -> Image.Image:
    scaled = scaled_box(box, source.size)
    pixels = np.asarray(source.convert("RGB"))
    region = pixels[scaled[1]:scaled[3], scaled[0]:scaled[2]]
    selected = selector(region).astype(np.uint8) * 255
    region_mask = Image.fromarray(selected, mode="L")
    if dilation > 1:
        region_mask = region_mask.filter(ImageFilter.MaxFilter(dilation))
    if blur > 0:
        region_mask = region_mask.filter(ImageFilter.GaussianBlur(blur))
    mask = Image.new("L", source.size, 0)
    mask.paste(region_mask, scaled[:2])
    return mask


def save_cutout(source: Image.Image, name: str, mask: Image.Image) -> None:
    layer = Image.new("RGBA", source.size)
    layer.paste(source, mask=Image.composite(source.getchannel("A"), Image.new("L", source.size), mask))
    layer.save(LAYERS / f"{name}.png")


def main() -> None:
    MASKS.mkdir(parents=True, exist_ok=True)
    LAYERS.mkdir(parents=True, exist_ok=True)
    source = Image.open(SOURCE).convert("RGBA")
    size = source.size

    components = {
        "eye_l": ellipse_mask(size, (520, 476, 576, 558)),
        "eye_r": ellipse_mask(size, (680, 476, 736, 558)),
        "blush_l": color_mask(
            source,
            (470, 525, 555, 600),
            lambda region: (region[:, :, 0] > region[:, :, 1] + 8) & (region[:, :, 0] > region[:, :, 2] + 12),
            dilation=7,
            blur=2,
        ),
        "blush_r": color_mask(
            source,
            (710, 525, 810, 600),
            lambda region: (region[:, :, 0] > region[:, :, 1] + 8) & (region[:, :, 0] > region[:, :, 2] + 12),
            dilation=7,
            blur=2,
        ),
        "nose": color_mask(
            source,
            (598, 525, 658, 570),
            lambda region: (region[:, :, 0] < 180) & (region[:, :, 1] < 170) & (region[:, :, 2] < 160),
            dilation=5,
            blur=1,
        ),
        "mouth_closed": color_mask(
            source,
            (585, 545, 685, 615),
            lambda region: (region[:, :, 0] < 180) & (region[:, :, 1] < 170) & (region[:, :, 2] < 160),
            dilation=5,
            blur=1,
        ),
        "forehead_mark": polygon_mask(size, [(627, 327), (653, 385), (628, 440), (602, 385)]),
    }

    edit_area = Image.new("L", size, 0)
    for name, mask in components.items():
        save_cutout(source, name, mask)
        edit_area = ImageChops.lighter(edit_area, mask)

    api_mask = Image.new("RGBA", size, (255, 255, 255, 255))
    api_mask.putalpha(Image.eval(edit_area, lambda value: 255 - value))
    api_mask.save(MASKS / "face_features_mask.png")

    mouth_edit_area = ellipse_mask(size, (575, 535, 695, 630))
    mouth_api_mask = Image.new("RGBA", size, (255, 255, 255, 255))
    mouth_api_mask.putalpha(Image.eval(mouth_edit_area, lambda value: 255 - value))
    mouth_api_mask.save(MASKS / "mouth_edit_mask.png")

    if MOUTH_OPEN_SOURCE.exists():
        mouth_source = Image.open(MOUTH_OPEN_SOURCE).convert("RGBA")
        mouth_pixels = np.asarray(mouth_source.convert("RGB"))
        red = mouth_pixels[:, :, 0].astype(int)
        green = mouth_pixels[:, :, 1].astype(int)
        blue = mouth_pixels[:, :, 2].astype(int)
        rows, columns = np.indices(red.shape)
        selected = (
            (
                ((red > green + 22) & (red > blue + 18) & (green < 180))
                | ((red < 110) & (green < 100) & (blue < 95))
            )
            & (rows > 500)
            & (rows < 550)
            & (columns > 600)
            & (columns < 660)
        )
        mouth_mask = Image.fromarray(selected.astype(np.uint8) * 255, mode="L")
        mouth_mask = mouth_mask.filter(ImageFilter.MaxFilter(3)).filter(ImageFilter.GaussianBlur(0.8))
        mouth_cutout = Image.new("RGBA", mouth_source.size)
        mouth_cutout.paste(mouth_source, mask=mouth_mask)
        registered_mouth = Image.new("RGBA", mouth_source.size)
        registered_mouth.alpha_composite(mouth_cutout, (0, 50))
        registered_mouth.save(LAYERS / "mouth_open.png")


if __name__ == "__main__":
    main()

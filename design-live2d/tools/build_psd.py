from pathlib import Path

import numpy as np
from PIL import Image
from pytoshop import codecs, enums
from pytoshop.user import nested_layers


ROOT = Path(__file__).resolve().parents[1]
LAYERS = ROOT / "layers"
OUTPUT = ROOT / "paper-messenger-live2d.psd"


def encode_packbits(data: bytes) -> bytes:
    source = bytes(data)
    if not source:
        return b""
    if len(source) == 1:
        return b"\x00" + source

    encoded = bytearray()
    literal = bytearray()
    index = 0
    repeat_count = 0
    repeating = False

    def flush_literal() -> None:
        if not literal:
            return
        encoded.append(len(literal) - 1)
        encoded.extend(literal)
        literal.clear()

    def flush_repeat(position: int) -> None:
        encoded.extend((257 - repeat_count, source[position]))

    while index < len(source) - 1:
        current = source[index]
        if current == source[index + 1]:
            if repeating:
                if repeat_count == 127:
                    flush_repeat(index)
                    repeat_count = 0
                repeat_count += 1
            else:
                flush_literal()
                repeating = True
                repeat_count = 1
        elif repeating:
            repeat_count += 1
            flush_repeat(index)
            repeating = False
            repeat_count = 0
        else:
            if len(literal) == 127:
                flush_literal()
            literal.append(current)
        index += 1

    if repeating:
        repeat_count += 1
        flush_repeat(index)
    else:
        literal.append(source[index])
        flush_literal()
    return bytes(encoded)


class PackBits:
    encode = staticmethod(encode_packbits)


codecs.packbits = PackBits


def image_layer(name: str, filename: str) -> nested_layers.Image:
    rgba = np.asarray(Image.open(LAYERS / filename).convert("RGBA"), dtype=np.uint8)
    return nested_layers.Image(
        name=name,
        channels={
            enums.ChannelId.red: rgba[:, :, 0],
            enums.ChannelId.green: rgba[:, :, 1],
            enums.ChannelId.blue: rgba[:, :, 2],
            enums.ChannelId.transparency: rgba[:, :, 3],
        },
        color_mode=enums.ColorMode.rgb,
    )


def main() -> None:
    base = Image.open(LAYERS / "character_base_no_face.png")
    width, height = base.size
    layers = [
        image_layer("forehead_mark", "forehead_mark.png"),
        image_layer("eye_l", "eye_l.png"),
        image_layer("eye_r", "eye_r.png"),
        image_layer("blush_l", "blush_l.png"),
        image_layer("blush_r", "blush_r.png"),
        image_layer("nose", "nose.png"),
        image_layer("mouth_open", "mouth_open.png"),
        image_layer("mouth_closed", "mouth_closed.png"),
        image_layer("character_base_no_face", "character_base_no_face.png"),
    ]
    psd = nested_layers.nested_layers_to_psd(
        layers,
        color_mode=enums.ColorMode.rgb,
        compression=enums.Compression.rle,
        depth=enums.ColorDepth.depth8,
        size=(height, width),
    )
    with OUTPUT.open("wb") as file_handle:
        psd.write(file_handle)


if __name__ == "__main__":
    main()

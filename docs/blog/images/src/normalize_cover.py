"""
把浏览器整页截图归一化成标准封面尺寸（900x383）。
浏览器面板可能带非 100% 缩放，整页截图里 html/body 区域只占左上角一小块，
且边界外是 html 默认纯白背景 —— 靠这一点自动探测设计区，再缩放到目标尺寸。
"""
import sys, os
from PIL import Image


def detect_design_box(im):
    """返回设计区 (w, h)：从左上角起、非洋红画布区域的实际像素尺寸。

    页面里 html 被显式设成洋红（#ff00ff）作为边界标记，body 的设计区盖在其上，
    浏览器面板缩放会让设计区只占画布左上角一小块，靠颜色突变即可稳定探测。
    """
    px = im.convert('RGB').load()
    W, H = im.size
    need = 3
    probe_x = min(6, W - 1)

    # 边界判据：洋红及其抗锯齿混色。设计区用到的颜色与洋红区分度很高——
    #   红区 (254,226,226) / 蓝区 (232,239,255) 的 g 都在 226+，
    #   深色标题条 (39,44,63) 的 r/b 远低于 200，
    #   而洋红 (255,0,255) 及混色都是 r,b 高、g 接近 0。
    def is_edge_marker(c):
        return c[0] > 200 and c[2] > 200 and c[1] < 200

    def is_pure_marker(c):
        return c[0] > 245 and c[1] < 30 and c[2] > 245

    # 两遍扫描。
    #
    # 为什么必须分两遍：探测线打在深色元素上（如 cover B 顶部的深色标题条）时，
    # 深色与洋红之间有一整条宽达十几像素的混色渐变带，这段带子不满足标记判据，
    # 直接扫会把这截渐变带一起裁进来，右边就留一条紫边。
    # 反之，探测线打在浅色元素上时，浅色与洋红几乎一步就到判据边界，定位精确。
    #
    # 所以：先用左侧列定出高度 h，再在 [0,h) 内挑几个浅色高度各扫一次宽度，取最小值。

    # 第 1 遍：左列扫描定高度（x 极小处必是设计区左缘的浅色区）
    h = H
    run = 0
    for y in range(0, H):
        if is_edge_marker(px[probe_x, y]):
            run += 1
            if run >= need:
                h = y - run + 1
                break
        else:
            run = 0
    h = max(1, h)

    # 第 2 遍：在多个高度上扫描宽度，取最紧的那个
    def scan_w(y):
        if y < 0 or y >= H:
            return W
        run = 0
        for x in range(0, W):
            if is_edge_marker(px[x, y]):
                run += 1
                if run >= need:
                    return x - run + 1
            else:
                run = 0
        return W

    probes = {min(6, h - 1),
              int(h * 0.20), int(h * 0.35), int(h * 0.50),
              int(h * 0.65), int(h * 0.80), int(h * 0.92)}
    w = min(scan_w(y) for y in probes if 0 <= y < h) if h > 1 else W

    return max(1, w), h


def normalize(src, dst, target=(1800, 766)):
    im = Image.open(src).convert('RGB')
    bw, bh = detect_design_box(im)
    box = im.crop((0, 0, bw, bh))
    out = box.resize(target, Image.LANCZOS)
    out.save(dst, 'PNG', optimize=True)
    # 校验：输出四边不得残留洋红标记像素
    px = out.load()
    W, H = out.size
    edge = []
    for x in range(0, W, 7):
        edge.append(px[x, 0])
        edge.append(px[x, H - 1])
    for y in range(0, H, 7):
        edge.append(px[0, y])
        edge.append(px[W - 1, y])
    bad = [c for c in edge if c[0] > 200 and c[2] > 200 and c[1] < 200]
    print(f'{os.path.basename(src)}')
    print(f'   原始 {im.size[0]}x{im.size[1]} -> 设计区 {bw}x{bh}'
          f' (采样缩放 {bw/target[0]:.3f}x) -> 输出 {out.size[0]}x{out.size[1]}')
    print(f'   边缘纯洋红残留: {len(bad)} 个 -> {"干净" if not bad else "仍有杂边，需检查"}')
    return dst


if __name__ == '__main__':
    normalize(sys.argv[1], sys.argv[2])
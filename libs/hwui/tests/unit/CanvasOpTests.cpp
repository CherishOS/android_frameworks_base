/*
 * Copyright (C) 2020 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

#include <gtest/gtest.h>

#include <canvas/CanvasFrontend.h>
#include <canvas/CanvasOpBuffer.h>
#include <canvas/CanvasOps.h>
#include <canvas/CanvasOpRasterizer.h>

#include <tests/common/CallCountingCanvas.h>

#include "SkPictureRecorder.h"
#include "SkColor.h"
#include "SkLatticeIter.h"
#include "pipeline/skia/AnimatedDrawables.h"
#include <SkNoDrawCanvas.h>

using namespace android;
using namespace android::uirenderer;
using namespace android::uirenderer::test;

// We lazy
using Op = CanvasOpType;

enum MockTypes {
    Lifecycle,
    COUNT
};

template<MockTypes T>
struct MockOp;

template<MockTypes T>
struct MockOpContainer {
    OpBufferItemHeader<MockTypes> header;
    MockOp<T> impl;
};

struct LifecycleTracker {
    int ctor_count = 0;
    int dtor_count = 0;

    int alive() { return ctor_count - dtor_count; }
};

template<>
struct MockOp<MockTypes::Lifecycle> {
    MockOp() = delete;
    void operator=(const MockOp&) = delete;

    MockOp(LifecycleTracker* tracker) : tracker(tracker) {
        tracker->ctor_count += 1;
    }

    MockOp(const MockOp& other) {
        tracker = other.tracker;
        tracker->ctor_count += 1;
    }

    ~MockOp() {
        tracker->dtor_count += 1;
    }

    LifecycleTracker* tracker = nullptr;
};

using MockBuffer = OpBuffer<MockTypes, MockOpContainer>;

class CanvasOpCountingReceiver {
public:
    template <CanvasOpType T>
    void push_container(CanvasOpContainer<T>&& op) {
        mOpCounts[static_cast<size_t>(T)] += 1;
    }

    int operator[](CanvasOpType op) const {
        return mOpCounts[static_cast<size_t>(op)];
    }

private:
    std::array<int, static_cast<size_t>(CanvasOpType::COUNT)> mOpCounts;
};

template<typename T>
static int countItems(const T& t) {
    int count = 0;
    t.for_each([&](auto i) {
        count++;
    });
    return count;
}

TEST(CanvasOp, lifecycleCheck) {
    LifecycleTracker tracker;
    {
        MockBuffer buffer;
        buffer.push_container(MockOpContainer<MockTypes::Lifecycle> {
            .impl = MockOp<MockTypes::Lifecycle>{&tracker}
        });
        EXPECT_EQ(tracker.alive(), 1);
        buffer.clear();
        EXPECT_EQ(tracker.alive(), 0);
    }
    EXPECT_EQ(tracker.alive(), 0);
}

TEST(CanvasOp, lifecycleCheckMove) {
    LifecycleTracker tracker;
    {
        MockBuffer buffer;
        buffer.push_container(MockOpContainer<MockTypes::Lifecycle> {
            .impl = MockOp<MockTypes::Lifecycle>{&tracker}
        });
        EXPECT_EQ(tracker.alive(), 1);
        {
            MockBuffer other(std::move(buffer));
            EXPECT_EQ(tracker.alive(), 1);
            EXPECT_EQ(buffer.size(), 0);
            EXPECT_GT(other.size(), 0);
            EXPECT_EQ(1, countItems(other));
            EXPECT_EQ(0, countItems(buffer));

            other.push_container(MockOpContainer<MockTypes::Lifecycle> {
                .impl = MockOp<MockTypes::Lifecycle>{&tracker}
            });

            EXPECT_EQ(2, countItems(other));
            EXPECT_EQ(2, tracker.alive());

            buffer.push_container(MockOpContainer<MockTypes::Lifecycle> {
                .impl = MockOp<MockTypes::Lifecycle>{&tracker}
            });
            EXPECT_EQ(1, countItems(buffer));
            EXPECT_EQ(3, tracker.alive());

            buffer = std::move(other);
            EXPECT_EQ(2, countItems(buffer));
            EXPECT_EQ(2, tracker.alive());
        }
        EXPECT_EQ(2, countItems(buffer));
        EXPECT_EQ(2, tracker.alive());
        buffer.clear();
        EXPECT_EQ(0, countItems(buffer));
        EXPECT_EQ(0, tracker.alive());
    }
    EXPECT_EQ(tracker.alive(), 0);
}

TEST(CanvasOp, verifyConst) {
    CanvasOpBuffer buffer;
    buffer.push<Op::DrawColor>({
        .color = SkColors::kBlack,
        .mode = SkBlendMode::kSrcOver,
    });
    buffer.for_each([](auto op) {
        static_assert(std::is_const_v<std::remove_reference_t<decltype(*op)>>,
                "Expected container to be const");
        static_assert(std::is_const_v<std::remove_reference_t<decltype(op->op())>>,
                "Expected op to be const");
    });
}

TEST(CanvasOp, simplePush) {
    CanvasOpBuffer buffer;
    EXPECT_EQ(buffer.size(), 0);
    buffer.push<Op::Save>({});
    buffer.push<Op::Save>({});
    buffer.push<Op::Restore>({});
    EXPECT_GT(buffer.size(), 0);

    int saveCount = 0;
    int restoreCount = 0;
    int otherCount = 0;

    buffer.for_each([&](auto op) {
        switch (op->type()) {
            case Op::Save:
                saveCount++;
                break;
            case Op::Restore:
                restoreCount++;
                break;
            default:
                otherCount++;
                break;
        }
    });

    EXPECT_EQ(saveCount, 2);
    EXPECT_EQ(restoreCount, 1);
    EXPECT_EQ(otherCount, 0);

    buffer.clear();
    int itemCount = 0;
    buffer.for_each([&](auto op) {
        itemCount++;
    });
    EXPECT_EQ(itemCount, 0);
    buffer.resize(0);
    EXPECT_EQ(buffer.size(), 0);
}

TEST(CanvasOp, simpleDrawPaint) {
    CanvasOpBuffer buffer;
    EXPECT_EQ(buffer.size(), 0);
    buffer.push<Op::DrawColor> ({
        .color = SkColor4f{1, 1, 1, 1},
        .mode = SkBlendMode::kSrcIn
    });

    CallCountingCanvas canvas;
    EXPECT_EQ(0, canvas.sumTotalDrawCalls());
    rasterizeCanvasBuffer(buffer, &canvas);
    EXPECT_EQ(1, canvas.drawPaintCount);
    EXPECT_EQ(1, canvas.sumTotalDrawCalls());
}

TEST(CanvasOp, simpleDrawPoint) {
    CanvasOpBuffer buffer;
    EXPECT_EQ(buffer.size(), 0);
    buffer.push<Op::DrawPoint> ({
        .x = 12,
        .y = 42,
        .paint = SkPaint{}
    });

    CallCountingCanvas canvas;
    EXPECT_EQ(0, canvas.sumTotalDrawCalls());
    rasterizeCanvasBuffer(buffer, &canvas);
    EXPECT_EQ(1, canvas.drawPoints);
    EXPECT_EQ(1, canvas.sumTotalDrawCalls());
}

TEST(CanvasOp, simpleDrawLine) {
    CanvasOpBuffer buffer;
    EXPECT_EQ(buffer.size(), 0);
    buffer.push<Op::DrawLine> ({
        .startX = 16,
        .startY = 28,
        .endX = 12,
        .endY = 30,
        .paint = SkPaint{}
    });

    CallCountingCanvas canvas;
    EXPECT_EQ(0, canvas.sumTotalDrawCalls());
    rasterizeCanvasBuffer(buffer, &canvas);
    EXPECT_EQ(1, canvas.drawPoints);
    EXPECT_EQ(1, canvas.sumTotalDrawCalls());
}

TEST(CanvasOp, simpleDrawRect) {
    CanvasOpBuffer buffer;
    EXPECT_EQ(buffer.size(), 0);
    buffer.push<Op::DrawRect> ({
        .paint = SkPaint{},
        .rect = SkRect::MakeEmpty()
    });

    CallCountingCanvas canvas;
    EXPECT_EQ(0, canvas.sumTotalDrawCalls());
    rasterizeCanvasBuffer(buffer, &canvas);
    EXPECT_EQ(1, canvas.drawRectCount);
    EXPECT_EQ(1, canvas.sumTotalDrawCalls());
}

TEST(CanvasOp, simpleDrawRegionRect) {
    CanvasOpBuffer buffer;
    EXPECT_EQ(buffer.size(), 0);
    SkRegion region;
    region.setRect(SkIRect::MakeWH(12, 50));
    buffer.push<Op::DrawRegion> ({
        .paint = SkPaint{},
        .region = region
    });

    CallCountingCanvas canvas;
    EXPECT_EQ(0, canvas.sumTotalDrawCalls());
    rasterizeCanvasBuffer(buffer, &canvas);
    // If the region is a rectangle, drawRegion calls into drawRect as a fast path
    EXPECT_EQ(1, canvas.drawRectCount);
    EXPECT_EQ(1, canvas.sumTotalDrawCalls());
}

TEST(CanvasOp, simpleDrawRegionPath) {
    CanvasOpBuffer buffer;
    EXPECT_EQ(buffer.size(), 0);
    SkPath path;
    path.addCircle(50, 50, 50);
    SkRegion clip;
    clip.setRect(SkIRect::MakeWH(100, 100));
    SkRegion region;
    region.setPath(path, clip);
    buffer.push<Op::DrawRegion> ({
        .paint = SkPaint{},
        .region = region
    });

    CallCountingCanvas canvas;
    EXPECT_EQ(0, canvas.sumTotalDrawCalls());
    rasterizeCanvasBuffer(buffer, &canvas);
    EXPECT_EQ(1, canvas.drawRegionCount);
    EXPECT_EQ(1, canvas.sumTotalDrawCalls());
}

TEST(CanvasOp, simpleDrawRoundRect) {
    CanvasOpBuffer buffer;
    EXPECT_EQ(buffer.size(), 0);
    buffer.push<Op::DrawRoundRect> ({
        .paint = SkPaint{},
        .rect = SkRect::MakeEmpty(),
        .rx = 10,
        .ry = 10
    });

    CallCountingCanvas canvas;
    EXPECT_EQ(0, canvas.sumTotalDrawCalls());
    rasterizeCanvasBuffer(buffer, &canvas);
    EXPECT_EQ(1, canvas.drawRRectCount);
    EXPECT_EQ(1, canvas.sumTotalDrawCalls());
}

TEST(CanvasOp, simpleDrawDoubleRoundRect) {
    CanvasOpBuffer buffer;
    EXPECT_EQ(buffer.size(), 0);
    SkRect outer = SkRect::MakeLTRB(0, 0, 100, 100);
    SkRect inner = SkRect::MakeLTRB(20, 20, 80, 80);

    const int numPts = 4;
    SkRRect outerRRect;

    auto outerPts = std::make_unique<SkVector[]>(numPts);
    outerPts[0].set(32, 16);
    outerPts[1].set(48, 48);
    outerPts[2].set(16, 32);
    outerPts[3].set(20, 20);
    outerRRect.setRectRadii(outer, outerPts.get());
    outerRRect.setRect(outer);

    SkRRect innerRRect;
    auto innerPts = std::make_unique<SkVector[]>(numPts);
    innerPts[0].set(16, 8);
    innerPts[1].set(24, 24);
    innerPts[2].set(8, 16);
    innerPts[3].set(10, 10);
    innerRRect.setRectRadii(inner, innerPts.get());

    buffer.push<Op::DrawDoubleRoundRect> ({
        .outer = outerRRect,
        .inner = innerRRect,
        .paint = SkPaint{}
    });

    CallCountingCanvas canvas;
    EXPECT_EQ(0, canvas.sumTotalDrawCalls());
    rasterizeCanvasBuffer(buffer, &canvas);
    EXPECT_EQ(1, canvas.drawDRRectCount);
    EXPECT_EQ(1, canvas.sumTotalDrawCalls());
}

TEST(CanvasOp, simpleDrawCircle) {
    CanvasOpBuffer buffer;
    EXPECT_EQ(buffer.size(), 0);
    buffer.push<Op::DrawCircle>({
        .cx = 5,
        .cy = 7,
        .radius = 10,
        .paint = SkPaint{}
    });

    CallCountingCanvas canvas;
    EXPECT_EQ(0, canvas.sumTotalDrawCalls());
    rasterizeCanvasBuffer(buffer, &canvas);
    EXPECT_EQ(1, canvas.drawOvalCount);
    EXPECT_EQ(1, canvas.sumTotalDrawCalls());
}

TEST(CanvasOp, simpleDrawOval) {
    CanvasOpBuffer buffer;
    EXPECT_EQ(buffer.size(), 0);
    buffer.push<Op::DrawOval> ({
        .oval = SkRect::MakeEmpty(),
        .paint = SkPaint{}
    });

    CallCountingCanvas canvas;
    EXPECT_EQ(0, canvas.sumTotalDrawCalls());
    rasterizeCanvasBuffer(buffer, &canvas);
    EXPECT_EQ(1, canvas.drawOvalCount);
    EXPECT_EQ(1, canvas.sumTotalDrawCalls());
}

TEST(CanvasOp, simpleDrawArc) {
    CanvasOpBuffer buffer;
    EXPECT_EQ(buffer.size(), 0);
    buffer.push<Op::DrawArc>({
        .oval = SkRect::MakeWH(100, 100),
        .startAngle = 120,
        .sweepAngle = 70,
        .useCenter = true,
        .paint = SkPaint{}
    });

    CallCountingCanvas canvas;
    EXPECT_EQ(0, canvas.sumTotalDrawCalls());
    rasterizeCanvasBuffer(buffer, &canvas);
    EXPECT_EQ(1, canvas.drawArcCount);
    EXPECT_EQ(1, canvas.sumTotalDrawCalls());
}

TEST(CanvasOp, simpleDrawPath) {
    CanvasOpBuffer buffer;
    EXPECT_EQ(buffer.size(), 0);
    SkPath path;
    path.addCircle(50, 50, 30);
    buffer.push<Op::DrawPath> ({
        .path = path,
        .paint = SkPaint{}
    });

    CallCountingCanvas canvas;
    EXPECT_EQ(0, canvas.sumTotalDrawCalls());
    rasterizeCanvasBuffer(buffer, &canvas);
    EXPECT_EQ(1, canvas.drawPathCount);
    EXPECT_EQ(1, canvas.sumTotalDrawCalls());
}

TEST(CanvasOp, simpleDrawRoundRectProperty) {
    CanvasOpBuffer buffer;
    EXPECT_EQ(buffer.size(), 0);

    auto left = sp<CanvasPropertyPrimitive>(new uirenderer::CanvasPropertyPrimitive(1));
    auto top = sp<CanvasPropertyPrimitive>(new uirenderer::CanvasPropertyPrimitive(2));
    auto right = sp<CanvasPropertyPrimitive>(new uirenderer::CanvasPropertyPrimitive(3));
    auto bottom = sp<CanvasPropertyPrimitive>(new uirenderer::CanvasPropertyPrimitive(4));
    auto radiusX = sp<CanvasPropertyPrimitive>(new uirenderer::CanvasPropertyPrimitive(5));
    auto radiusY = sp<CanvasPropertyPrimitive>(new uirenderer::CanvasPropertyPrimitive(6));
    auto propertyPaint =
            sp<uirenderer::CanvasPropertyPaint>(new uirenderer::CanvasPropertyPaint(SkPaint{}));

    buffer.push<Op::DrawRoundRectProperty> ({
        .left = left,
        .top = top,
        .right = right,
        .bottom = bottom,
        .rx = radiusX,
        .ry = radiusY,
        .paint = propertyPaint
    });

    CallCountingCanvas canvas;
    EXPECT_EQ(0, canvas.sumTotalDrawCalls());
    rasterizeCanvasBuffer(buffer, &canvas);
    EXPECT_EQ(1, canvas.drawRRectCount);
    EXPECT_EQ(1, canvas.sumTotalDrawCalls());
}

TEST(CanvasOp, simpleDrawCircleProperty) {
    CanvasOpBuffer buffer;
    EXPECT_EQ(buffer.size(), 0);

    auto x = sp<CanvasPropertyPrimitive>(new uirenderer::CanvasPropertyPrimitive(1));
    auto y = sp<CanvasPropertyPrimitive>(new uirenderer::CanvasPropertyPrimitive(2));
    auto radius = sp<CanvasPropertyPrimitive>(new uirenderer::CanvasPropertyPrimitive(5));
    auto propertyPaint =
            sp<uirenderer::CanvasPropertyPaint>(new uirenderer::CanvasPropertyPaint(SkPaint{}));

    buffer.push<Op::DrawCircleProperty> ({
        .x = x,
        .y = y,
        .radius = radius,
        .paint = propertyPaint
    });

    CallCountingCanvas canvas;
    EXPECT_EQ(0, canvas.sumTotalDrawCalls());
    rasterizeCanvasBuffer(buffer, &canvas);
    EXPECT_EQ(1, canvas.drawOvalCount);
    EXPECT_EQ(1, canvas.sumTotalDrawCalls());
}

TEST(CanvasOp, simpleDrawVertices) {
    CanvasOpBuffer buffer;
    EXPECT_EQ(buffer.size(), 0);

    SkPoint pts[3] = {{64, 32}, {0, 224}, {128, 224}};
    SkColor colors[3] = {SK_ColorRED, SK_ColorBLUE, SK_ColorGREEN};
    sk_sp<SkVertices> vertices = SkVertices::MakeCopy(SkVertices::kTriangles_VertexMode, 3, pts,
            nullptr, colors);
    buffer.push<Op::DrawVertices> ({
        .vertices = vertices,
        .mode = SkBlendMode::kSrcOver,
        .paint = SkPaint{}
    });

    CallCountingCanvas canvas;
    EXPECT_EQ(0, canvas.sumTotalDrawCalls());
    rasterizeCanvasBuffer(buffer, &canvas);
    EXPECT_EQ(1, canvas.drawVerticesCount);
    EXPECT_EQ(1, canvas.sumTotalDrawCalls());
}

TEST(CanvasOp, simpleDrawImage) {
    CanvasOpBuffer buffer;
    EXPECT_EQ(buffer.size(), 0);

    SkImageInfo info =SkImageInfo::Make(5, 1,
        kGray_8_SkColorType, kOpaque_SkAlphaType);
    sk_sp<Bitmap> bitmap = Bitmap::allocateHeapBitmap(info);
    buffer.push<Op::DrawImage> ({
            bitmap,
            7,
            19,
            SkPaint{}
        }
    );

    CallCountingCanvas canvas;
    EXPECT_EQ(0, canvas.sumTotalDrawCalls());
    rasterizeCanvasBuffer(buffer, &canvas);
    EXPECT_EQ(1, canvas.drawImageCount);
    EXPECT_EQ(1, canvas.sumTotalDrawCalls());
}

TEST(CanvasOp, simpleDrawImageRect) {
    CanvasOpBuffer buffer;
    EXPECT_EQ(buffer.size(), 0);

    SkImageInfo info = SkImageInfo::Make(5, 1,
        kGray_8_SkColorType, kOpaque_SkAlphaType);

    sk_sp<Bitmap> bitmap = Bitmap::allocateHeapBitmap(info);
    buffer.push<Op::DrawImageRect> ({
          bitmap, SkRect::MakeWH(100, 100),
          SkRect::MakeLTRB(120, 110, 220, 210),
          SkPaint{}
        }
    );

    CallCountingCanvas canvas;
    EXPECT_EQ(0, canvas.sumTotalDrawCalls());
    rasterizeCanvasBuffer(buffer, &canvas);
    EXPECT_EQ(1, canvas.drawImageRectCount);
    EXPECT_EQ(1, canvas.sumTotalDrawCalls());
}

TEST(CanvasOp, simpleDrawImageLattice) {
    CanvasOpBuffer buffer;
    EXPECT_EQ(buffer.size(), 0);

    SkBitmap skBitmap;
    skBitmap.allocPixels(SkImageInfo::MakeN32Premul(60, 60));

    const int xDivs[] = { 20, 50 };
    const int yDivs[] = { 10, 40 };
    SkCanvas::Lattice::RectType fillTypes[3][3];
    memset(fillTypes, 0, sizeof(fillTypes));
    fillTypes[1][1] = SkCanvas::Lattice::kTransparent;
    SkColor colors[9];
    SkCanvas::Lattice lattice = { xDivs, yDivs, fillTypes[0], 2,
         2, nullptr, colors };
    sk_sp<Bitmap> bitmap = Bitmap::allocateHeapBitmap(&skBitmap);
    buffer.push<Op::DrawImageLattice>(
        {
            bitmap,
            SkRect::MakeWH(5, 1),
            lattice,
            SkPaint{}
        }
    );

    CallCountingCanvas canvas;
    EXPECT_EQ(0, canvas.sumTotalDrawCalls());
    rasterizeCanvasBuffer(buffer, &canvas);
    EXPECT_EQ(1, canvas.drawImageLatticeCount);
    EXPECT_EQ(1, canvas.sumTotalDrawCalls());
}

TEST(CanvasOp, simpleDrawPicture) {
    CanvasOpBuffer buffer;
    EXPECT_EQ(buffer.size(), 0);

    SkPictureRecorder recorder;
    SkCanvas* pictureCanvas = recorder.beginRecording({64, 64, 192, 192});
    SkPaint paint;
    pictureCanvas->drawRect(SkRect::MakeWH(200, 200), paint);
    paint.setColor(SK_ColorWHITE);
    pictureCanvas->drawRect(SkRect::MakeLTRB(20, 20, 180, 180), paint);
    sk_sp<SkPicture> picture = recorder.finishRecordingAsPicture();
    buffer.push<Op::DrawPicture> ({
        .picture = picture
    });

    CallCountingCanvas canvas;
    EXPECT_EQ(0, canvas.sumTotalDrawCalls());
    rasterizeCanvasBuffer(buffer, &canvas);
    // Note because we are explicitly issuing 2 drawRect calls
    // in the picture recorder above, when it is played back into
    // CallCountingCanvas we will see 2 calls to drawRect instead of 1
    // call to drawPicture.
    // This is because SkiaCanvas::drawPicture uses picture.playback(canvas)
    // instead of canvas->drawPicture.
    EXPECT_EQ(2, canvas.drawRectCount);
    EXPECT_EQ(2, canvas.sumTotalDrawCalls());
}

TEST(CanvasOp, immediateRendering) {
    auto canvas = std::make_shared<CallCountingCanvas>();

    EXPECT_EQ(0, canvas->sumTotalDrawCalls());
    ImmediateModeRasterizer rasterizer{canvas};
    auto op = CanvasOp<Op::DrawRect> {
        .paint = SkPaint{},
        .rect = SkRect::MakeEmpty()
    };
    EXPECT_TRUE(CanvasOpTraits::can_draw<decltype(op)>);
    rasterizer.draw(op);
    EXPECT_EQ(1, canvas->drawRectCount);
    EXPECT_EQ(1, canvas->sumTotalDrawCalls());
}

TEST(CanvasOp, frontendSaveCount) {
    SkNoDrawCanvas skiaCanvas(100, 100);
    CanvasFrontend<CanvasOpCountingReceiver> opCanvas(100, 100);
    const auto& receiver = opCanvas.receiver();

    EXPECT_EQ(1, skiaCanvas.getSaveCount());
    EXPECT_EQ(1, opCanvas.saveCount());

    skiaCanvas.save();
    opCanvas.save(SaveFlags::MatrixClip);
    EXPECT_EQ(2, skiaCanvas.getSaveCount());
    EXPECT_EQ(2, opCanvas.saveCount());

    skiaCanvas.restore();
    opCanvas.restore();
    EXPECT_EQ(1, skiaCanvas.getSaveCount());
    EXPECT_EQ(1, opCanvas.saveCount());

    skiaCanvas.restore();
    opCanvas.restore();
    EXPECT_EQ(1, skiaCanvas.getSaveCount());
    EXPECT_EQ(1, opCanvas.saveCount());

    EXPECT_EQ(1, receiver[Op::Save]);
    EXPECT_EQ(1, receiver[Op::Restore]);
}

TEST(CanvasOp, frontendTransform) {

}
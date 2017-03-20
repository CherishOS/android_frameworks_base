/*
 * Copyright (C) 2015 The Android Open Source Project
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

#include "SkiaCanvasProxy.h"

#include <memory>

#include <log/log.h>

#include "hwui/Bitmap.h"
#include <SkLatticeIter.h>
#include <SkPatchUtils.h>
#include <SkPaint.h>
#include <SkPath.h>
#include <SkPixelRef.h>
#include <SkRect.h>
#include <SkRRect.h>
#include <SkRSXform.h>
#include <SkSurface.h>
#include <SkTextBlobRunIterator.h>
#include <SkVertices.h>

namespace android {
namespace uirenderer {

SkiaCanvasProxy::SkiaCanvasProxy(Canvas* canvas, bool filterHwuiCalls)
        : INHERITED(canvas->width(), canvas->height())
        , mCanvas(canvas)
        , mFilterHwuiCalls(filterHwuiCalls) {}

void SkiaCanvasProxy::onDrawPaint(const SkPaint& paint) {
    mCanvas->drawPaint(paint);
}

void SkiaCanvasProxy::onDrawPoints(PointMode pointMode, size_t count, const SkPoint pts[],
        const SkPaint& paint) {
    if (!pts || count == 0) {
        return;
    }

    // convert the SkPoints into floats
    static_assert(sizeof(SkPoint) == sizeof(float)*2, "SkPoint is no longer two floats");
    const size_t floatCount = count << 1;
    const float* floatArray = &pts[0].fX;

    switch (pointMode) {
        case kPoints_PointMode: {
            mCanvas->drawPoints(floatArray, floatCount, paint);
            break;
        }
        case kLines_PointMode: {
            mCanvas->drawLines(floatArray, floatCount, paint);
            break;
        }
        case kPolygon_PointMode: {
            SkPaint strokedPaint(paint);
            strokedPaint.setStyle(SkPaint::kStroke_Style);

            SkPath path;
            for (size_t i = 0; i < count - 1; i++) {
                path.moveTo(pts[i]);
                path.lineTo(pts[i+1]);
                this->drawPath(path, strokedPaint);
                path.rewind();
            }
            break;
        }
        default:
            LOG_ALWAYS_FATAL("Unknown point type");
    }
}

void SkiaCanvasProxy::onDrawOval(const SkRect& rect, const SkPaint& paint) {
    mCanvas->drawOval(rect.fLeft, rect.fTop, rect.fRight, rect.fBottom, paint);
}

void SkiaCanvasProxy::onDrawRect(const SkRect& rect, const SkPaint& paint) {
    mCanvas->drawRect(rect.fLeft, rect.fTop, rect.fRight, rect.fBottom, paint);
}

void SkiaCanvasProxy::onDrawRRect(const SkRRect& roundRect, const SkPaint& paint) {
    if (!roundRect.isComplex()) {
        const SkRect& rect = roundRect.rect();
        SkVector radii = roundRect.getSimpleRadii();
        mCanvas->drawRoundRect(rect.fLeft, rect.fTop, rect.fRight, rect.fBottom,
                               radii.fX, radii.fY, paint);
    } else {
        SkPath path;
        path.addRRect(roundRect);
        mCanvas->drawPath(path, paint);
    }
}

void SkiaCanvasProxy::onDrawArc(const SkRect& rect, SkScalar startAngle, SkScalar sweepAngle,
                                bool useCenter, const SkPaint& paint) {
    mCanvas->drawArc(rect.fLeft, rect.fTop, rect.fRight, rect.fBottom,
                     startAngle, sweepAngle, useCenter, paint);
}

void SkiaCanvasProxy::onDrawPath(const SkPath& path, const SkPaint& paint) {
    mCanvas->drawPath(path, paint);
}

void SkiaCanvasProxy::onDrawBitmap(const SkBitmap& bitmap, SkScalar left, SkScalar top,
        const SkPaint* paint) {
    sk_sp<Bitmap> hwuiBitmap = Bitmap::createFrom(bitmap.info(), *bitmap.pixelRef());
    // HWUI doesn't support extractSubset(), so convert any subsetted bitmap into
    // a drawBitmapRect(); pass through an un-subsetted bitmap.
    if (hwuiBitmap && bitmap.dimensions() != hwuiBitmap->info().dimensions()) {
        SkIPoint origin = bitmap.pixelRefOrigin();
        mCanvas->drawBitmap(*hwuiBitmap, origin.fX, origin.fY,
                            origin.fX + bitmap.dimensions().width(),
                            origin.fY + bitmap.dimensions().height(),
                            left, top,
                            left + bitmap.dimensions().width(),
                            top + bitmap.dimensions().height(),
                            paint);
    } else {
        mCanvas->drawBitmap(*hwuiBitmap, left, top, paint);
    }
}

void SkiaCanvasProxy::onDrawBitmapRect(const SkBitmap& skBitmap, const SkRect* srcPtr,
        const SkRect& dst, const SkPaint* paint, SrcRectConstraint) {
    SkRect src = (srcPtr) ? *srcPtr : SkRect::MakeWH(skBitmap.width(), skBitmap.height());
    // TODO: if bitmap is a subset, do we need to add pixelRefOrigin to src?
   Bitmap* bitmap = reinterpret_cast<Bitmap*>(skBitmap.pixelRef());
   mCanvas->drawBitmap(*bitmap, src.fLeft, src.fTop, src.fRight, src.fBottom,
                        dst.fLeft, dst.fTop, dst.fRight, dst.fBottom, paint);
}

void SkiaCanvasProxy::onDrawBitmapNine(const SkBitmap& bitmap, const SkIRect& center,
        const SkRect& dst, const SkPaint*) {
    //TODO make nine-patch drawing a method on Canvas.h
    SkDEBUGFAIL("SkiaCanvasProxy::onDrawBitmapNine is not yet supported");
}

void SkiaCanvasProxy::onDrawImage(const SkImage* image, SkScalar left, SkScalar top,
        const SkPaint* paint) {
    SkBitmap skiaBitmap;
    if (image->asLegacyBitmap(&skiaBitmap, SkImage::kRO_LegacyBitmapMode)) {
        onDrawBitmap(skiaBitmap, left, top, paint);
    }
}

void SkiaCanvasProxy::onDrawImageRect(const SkImage* image, const SkRect* srcPtr, const SkRect& dst,
        const SkPaint* paint, SrcRectConstraint constraint) {
    SkBitmap skiaBitmap;
    if (image->asLegacyBitmap(&skiaBitmap, SkImage::kRO_LegacyBitmapMode)) {
        sk_sp<Bitmap> bitmap = Bitmap::createFrom(skiaBitmap.info(), *skiaBitmap.pixelRef());
        SkRect src = (srcPtr) ? *srcPtr : SkRect::MakeWH(image->width(), image->height());
        mCanvas->drawBitmap(*bitmap, src.fLeft, src.fTop, src.fRight, src.fBottom,
                dst.fLeft, dst.fTop, dst.fRight, dst.fBottom, paint);
    }
}

void SkiaCanvasProxy::onDrawImageNine(const SkImage*, const SkIRect& center, const SkRect& dst,
        const SkPaint*) {
    SkDEBUGFAIL("SkiaCanvasProxy::onDrawImageNine is not yet supported");
}

void SkiaCanvasProxy::onDrawImageLattice(const SkImage* image, const Lattice& lattice,
        const SkRect& dst, const SkPaint* paint) {
    SkLatticeIter iter(lattice, dst);
    SkRect srcR, dstR;
    while (iter.next(&srcR, &dstR)) {
        onDrawImageRect(image, &srcR, dstR, paint, SkCanvas::kStrict_SrcRectConstraint);
    }
}

void SkiaCanvasProxy::onDrawVerticesObject(const SkVertices* vertices, SkBlendMode bmode,
        const SkPaint& paint) {
    // TODO: should we pass through blendmode
    if (mFilterHwuiCalls) {
        return;
    }
    // convert the SkPoints into floats
    static_assert(sizeof(SkPoint) == sizeof(float)*2, "SkPoint is no longer two floats");
    const int floatCount = vertices->vertexCount() << 1;
    const float* vArray = (const float*)vertices->positions();
    const float* tArray = (const float*)vertices->texCoords();
    const int* cArray = (const int*)vertices->colors();
    mCanvas->drawVertices(vertices->mode(), floatCount, vArray, tArray, cArray,
            vertices->indices(), vertices->indexCount(), paint);
}

sk_sp<SkSurface> SkiaCanvasProxy::onNewSurface(const SkImageInfo&, const SkSurfaceProps&) {
    SkDEBUGFAIL("SkiaCanvasProxy::onNewSurface is not supported");
    return NULL;
}

void SkiaCanvasProxy::willSave() {
    mCanvas->save(android::SaveFlags::MatrixClip);
}

static inline SaveFlags::Flags saveFlags(SkCanvas::SaveLayerFlags layerFlags) {
    SaveFlags::Flags saveFlags = 0;

    if (!(layerFlags & SkCanvas::kDontClipToLayer_Legacy_SaveLayerFlag)) {
        saveFlags |= SaveFlags::ClipToLayer;
    }

    if (!(layerFlags & SkCanvas::kIsOpaque_SaveLayerFlag)) {
        saveFlags |= SaveFlags::HasAlphaLayer;
    }

    return saveFlags;
}

SkCanvas::SaveLayerStrategy SkiaCanvasProxy::getSaveLayerStrategy(const SaveLayerRec& saveLayerRec) {
    SkRect rect;
    if (saveLayerRec.fBounds) {
        rect = *saveLayerRec.fBounds;
    } else if (!mCanvas->getClipBounds(&rect)) {
        rect = SkRect::MakeEmpty();
    }
    mCanvas->saveLayer(rect.fLeft, rect.fTop, rect.fRight, rect.fBottom, saveLayerRec.fPaint,
                       saveFlags(saveLayerRec.fSaveLayerFlags));
    return SkCanvas::kNoLayer_SaveLayerStrategy;
}

void SkiaCanvasProxy::willRestore() {
    mCanvas->restore();
}

void SkiaCanvasProxy::didConcat(const SkMatrix& matrix) {
    mCanvas->concat(matrix);
}

void SkiaCanvasProxy::didSetMatrix(const SkMatrix& matrix) {
    mCanvas->setMatrix(matrix);
}

void SkiaCanvasProxy::onDrawDRRect(const SkRRect& outer, const SkRRect& inner,
        const SkPaint& paint) {
    SkPath path;
    path.addRRect(outer);
    path.addRRect(inner);
    path.setFillType(SkPath::kEvenOdd_FillType);
    this->drawPath(path, paint);
}

/**
 * Utility class that converts the incoming text & paint from the given encoding
 * into glyphIDs.
 */
class GlyphIDConverter {
public:
    GlyphIDConverter(const void* text, size_t byteLength, const SkPaint& origPaint) {
        paint = origPaint;
        if (paint.getTextEncoding() == SkPaint::kGlyphID_TextEncoding) {
            glyphIDs = (uint16_t*)text;
            count = byteLength >> 1;
        } else {
             // ensure space for one glyph per ID given UTF8 encoding.
            storage.reset(new uint16_t[byteLength]);
            glyphIDs = storage.get();
            count = paint.textToGlyphs(text, byteLength, storage.get());
            paint.setTextEncoding(SkPaint::kGlyphID_TextEncoding);
        }
    }

    SkPaint paint;
    uint16_t* glyphIDs;
    int count;
private:
    std::unique_ptr<uint16_t[]> storage;
};

void SkiaCanvasProxy::onDrawText(const void* text, size_t byteLength, SkScalar x, SkScalar y,
        const SkPaint& origPaint) {
    // convert to glyphIDs if necessary
    GlyphIDConverter glyphs(text, byteLength, origPaint);

    // compute the glyph positions
    std::unique_ptr<SkPoint[]> pointStorage(new SkPoint[glyphs.count]);
    std::unique_ptr<SkScalar[]> glyphWidths(new SkScalar[glyphs.count]);
    glyphs.paint.getTextWidths(glyphs.glyphIDs, glyphs.count << 1, glyphWidths.get());

    // compute conservative bounds
    // NOTE: We could call the faster paint.getFontBounds for a less accurate,
    //       but even more conservative bounds if this  is too slow.
    SkRect bounds;
    glyphs.paint.measureText(glyphs.glyphIDs, glyphs.count << 1, &bounds);

    // adjust for non-left alignment
    if (glyphs.paint.getTextAlign() != SkPaint::kLeft_Align) {
        SkScalar stop = 0;
        for (int i = 0; i < glyphs.count; i++) {
            stop += glyphWidths[i];
        }
        if (glyphs.paint.getTextAlign() == SkPaint::kCenter_Align) {
            stop = SkScalarHalf(stop);
        }
        if (glyphs.paint.isVerticalText()) {
            y -= stop;
        } else {
            x -= stop;
        }
    }

    // setup the first glyph position and adjust bounds if needed
    int xBaseline = 0;
    int yBaseline = 0;
    if (mCanvas->drawTextAbsolutePos()) {
        bounds.offset(x,y);
        xBaseline = x;
        yBaseline = y;
    }
    pointStorage[0].set(xBaseline, yBaseline);

    // setup the remaining glyph positions
    if (glyphs.paint.isVerticalText()) {
        for (int i = 1; i < glyphs.count; i++) {
            pointStorage[i].set(xBaseline, glyphWidths[i-1] + pointStorage[i-1].fY);
        }
    } else {
        for (int i = 1; i < glyphs.count; i++) {
            pointStorage[i].set(glyphWidths[i-1] + pointStorage[i-1].fX, yBaseline);
        }
    }

    static_assert(sizeof(SkPoint) == sizeof(float)*2, "SkPoint is no longer two floats");
    mCanvas->drawGlyphs(glyphs.glyphIDs, &pointStorage[0].fX, glyphs.count, glyphs.paint,
                      x, y, bounds.fLeft, bounds.fTop, bounds.fRight, bounds.fBottom, 0);
}

void SkiaCanvasProxy::onDrawPosText(const void* text, size_t byteLength, const SkPoint pos[],
        const SkPaint& origPaint) {
    // convert to glyphIDs if necessary
    GlyphIDConverter glyphs(text, byteLength, origPaint);

    // convert to relative positions if necessary
    int x, y;
    const SkPoint* posArray;
    std::unique_ptr<SkPoint[]> pointStorage;
    if (mCanvas->drawTextAbsolutePos()) {
        x = 0;
        y = 0;
        posArray = pos;
    } else {
        x = pos[0].fX;
        y = pos[0].fY;
        pointStorage.reset(new SkPoint[glyphs.count]);
        for (int i = 0; i < glyphs.count; i++) {
            pointStorage[i].fX = pos[i].fX - x;
            pointStorage[i].fY = pos[i].fY - y;
        }
        posArray = pointStorage.get();
    }

    // Compute conservative bounds.  If the content has already been processed
    // by Minikin then it had already computed these bounds.  Unfortunately,
    // there is no way to capture those bounds as part of the Skia drawPosText
    // API so we need to do that computation again here.
    SkRect bounds = SkRect::MakeEmpty();
    for (int i = 0; i < glyphs.count; i++) {
        SkRect glyphBounds = SkRect::MakeEmpty();
        glyphs.paint.measureText(&glyphs.glyphIDs[i], sizeof(uint16_t), &glyphBounds);
        glyphBounds.offset(pos[i].fX, pos[i].fY);
        bounds.join(glyphBounds);
    }

    static_assert(sizeof(SkPoint) == sizeof(float)*2, "SkPoint is no longer two floats");
    mCanvas->drawGlyphs(glyphs.glyphIDs, &posArray[0].fX, glyphs.count, glyphs.paint, x, y,
                      bounds.fLeft, bounds.fTop, bounds.fRight, bounds.fBottom, 0);
}

void SkiaCanvasProxy::onDrawPosTextH(const void* text, size_t byteLength, const SkScalar xpos[],
        SkScalar constY, const SkPaint& paint) {
    const size_t pointCount = byteLength >> 1;
    std::unique_ptr<SkPoint[]> pts(new SkPoint[pointCount]);
    for (size_t i = 0; i < pointCount; i++) {
        pts[i].set(xpos[i], constY);
    }
    this->onDrawPosText(text, byteLength, pts.get(), paint);
}

void SkiaCanvasProxy::onDrawTextOnPath(const void* text, size_t byteLength, const SkPath& path,
        const SkMatrix* matrix, const SkPaint& origPaint) {
    SkDEBUGFAIL("SkiaCanvasProxy::onDrawTextOnPath is not supported");
}

void SkiaCanvasProxy::onDrawTextRSXform(const void* text, size_t byteLength,
        const SkRSXform xform[], const SkRect* cullRect, const SkPaint& paint) {
    GlyphIDConverter glyphs(text, byteLength, paint); // Just get count
    SkMatrix localM, currM, origM;
    mCanvas->getMatrix(&currM);
    origM = currM;
    for (int i = 0; i < glyphs.count; i++) {
        localM.setRSXform(*xform++);
        currM.setConcat(origM, localM);
        mCanvas->setMatrix(currM);
        this->onDrawText((char*)text + (byteLength / glyphs.count * i),
                         byteLength / glyphs.count, 0, 0, paint);
    }
    mCanvas->setMatrix(origM);
}


void SkiaCanvasProxy::onDrawTextBlob(const SkTextBlob* blob, SkScalar x, SkScalar y,
        const SkPaint& paint) {
    SkPaint runPaint = paint;

     SkTextBlobRunIterator it(blob);
     for (;!it.done(); it.next()) {
         size_t textLen = it.glyphCount() * sizeof(uint16_t);
         const SkPoint& offset = it.offset();
         // applyFontToPaint() always overwrites the exact same attributes,
         // so it is safe to not re-seed the paint for this reason.
         it.applyFontToPaint(&runPaint);

         switch (it.positioning()) {
         case SkTextBlob::kDefault_Positioning:
             this->drawText(it.glyphs(), textLen, x + offset.x(), y + offset.y(), runPaint);
             break;
         case SkTextBlob::kHorizontal_Positioning: {
             std::unique_ptr<SkPoint[]> pts(new SkPoint[it.glyphCount()]);
             for (size_t i = 0; i < it.glyphCount(); i++) {
                 pts[i].set(x + offset.x() + it.pos()[i], y + offset.y());
             }
             this->drawPosText(it.glyphs(), textLen, pts.get(), runPaint);
             break;
         }
         case SkTextBlob::kFull_Positioning: {
             std::unique_ptr<SkPoint[]> pts(new SkPoint[it.glyphCount()]);
             for (size_t i = 0; i < it.glyphCount(); i++) {
                 const size_t xIndex = i*2;
                 const size_t yIndex = xIndex + 1;
                 pts[i].set(x + offset.x() + it.pos()[xIndex], y + offset.y() + it.pos()[yIndex]);
             }
             this->drawPosText(it.glyphs(), textLen, pts.get(), runPaint);
             break;
         }
         default:
             SkFAIL("unhandled positioning mode");
         }
     }
}

void SkiaCanvasProxy::onDrawPatch(const SkPoint cubics[12], const SkColor colors[4],
        const SkPoint texCoords[4], SkBlendMode bmode, const SkPaint& paint) {
    if (mFilterHwuiCalls) {
        return;
    }
    SkPatchUtils::VertexData data;

    SkMatrix matrix;
    mCanvas->getMatrix(&matrix);
    SkISize lod = SkPatchUtils::GetLevelOfDetail(cubics, &matrix);

    // It automatically adjusts lodX and lodY in case it exceeds the number of indices.
    // If it fails to generate the vertices, then we do not draw.
    if (SkPatchUtils::getVertexData(&data, cubics, colors, texCoords, lod.width(), lod.height())) {
        this->drawVertices(SkCanvas::kTriangles_VertexMode, data.fVertexCount, data.fPoints,
                           data.fTexCoords, data.fColors, bmode, data.fIndices, data.fIndexCount,
                           paint);
    }
}

void SkiaCanvasProxy::onClipRect(const SkRect& rect, SkClipOp op, ClipEdgeStyle) {
    mCanvas->clipRect(rect.fLeft, rect.fTop, rect.fRight, rect.fBottom, op);
}

void SkiaCanvasProxy::onClipRRect(const SkRRect& roundRect, SkClipOp op, ClipEdgeStyle) {
    SkPath path;
    path.addRRect(roundRect);
    mCanvas->clipPath(&path, op);
}

void SkiaCanvasProxy::onClipPath(const SkPath& path, SkClipOp op, ClipEdgeStyle) {
    mCanvas->clipPath(&path, op);
}

}; // namespace uirenderer
}; // namespace android

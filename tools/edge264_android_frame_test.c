/*
 * Offline Android arm64 smoke test for Edge264 MVC decoding.
 *
 * Usage:
 *   edge264_android_frame_test input.264 [output-prefix]
 *
 * The input must be Annex B. When output-prefix is supplied, the first paired
 * base/MVC luma planes are written as <prefix>-base.pgm and <prefix>-mvc.pgm.
 */
#include <errno.h>
#include <inttypes.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <time.h>

#include "edge264.h"

typedef struct TestState {
  int frames;
  int mvc_frames;
  int distinct_mvc_frames;
  int decode_warnings;
  uint64_t first_base_hash;
  uint64_t first_mvc_hash;
  int wrote_pair;
  const char *output_prefix;
} TestState;

static uint64_t hash_plane(
    const uint8_t *samples, int stride, int width, int height) {
  uint64_t hash = UINT64_C(1469598103934665603);
  for (int y = 0; y < height; y++) {
    const uint8_t *row = samples + y * stride;
    for (int x = 0; x < width; x++) {
      hash ^= row[x];
      hash *= UINT64_C(1099511628211);
    }
  }
  return hash;
}

static int write_pgm(
    const char *path,
    const uint8_t *samples,
    int stride,
    int width,
    int height) {
  FILE *file = fopen(path, "wb");
  if (file == NULL) {
    fprintf(stderr, "Could not create %s: %s\n", path, strerror(errno));
    return -1;
  }
  fprintf(file, "P5\n%d %d\n255\n", width, height);
  for (int y = 0; y < height; y++) {
    if (fwrite(samples + y * stride, 1, width, file) != (size_t) width) {
      fprintf(stderr, "Could not write %s\n", path);
      fclose(file);
      return -1;
    }
  }
  fclose(file);
  return 0;
}

static int save_first_pair(const Edge264Frame *frame, TestState *state) {
  if (state->output_prefix == NULL || state->wrote_pair) {
    return 0;
  }
  size_t length = strlen(state->output_prefix);
  char *base_path = malloc(length + 16);
  char *mvc_path = malloc(length + 16);
  if (base_path == NULL || mvc_path == NULL) {
    free(base_path);
    free(mvc_path);
    return -1;
  }
  sprintf(base_path, "%s-base.pgm", state->output_prefix);
  sprintf(mvc_path, "%s-mvc.pgm", state->output_prefix);
  int result =
      write_pgm(
          base_path,
          frame->samples[0],
          frame->stride_Y,
          frame->width_Y,
          frame->height_Y);
  if (result == 0) {
    result =
        write_pgm(
            mvc_path,
            frame->samples_mvc[0],
            frame->stride_Y,
            frame->width_Y,
            frame->height_Y);
  }
  free(base_path);
  free(mvc_path);
  if (result == 0) {
    state->wrote_pair = 1;
  }
  return result;
}

static int drain_frames(Edge264Decoder *decoder, TestState *state) {
  Edge264Frame frame;
  int result;
  while ((result = edge264_get_frame(decoder, &frame, 0)) == 0) {
    state->frames++;
    if (frame.samples_mvc[0] != NULL) {
      state->mvc_frames++;
      uint64_t base_hash =
          hash_plane(
              frame.samples[0],
              frame.stride_Y,
              frame.width_Y,
              frame.height_Y);
      uint64_t mvc_hash =
          hash_plane(
              frame.samples_mvc[0],
              frame.stride_Y,
              frame.width_Y,
              frame.height_Y);
      if (base_hash != mvc_hash) {
        state->distinct_mvc_frames++;
      }
      if (base_hash != mvc_hash && state->first_base_hash == 0) {
        state->first_base_hash = base_hash;
        state->first_mvc_hash = mvc_hash;
        printf(
            "FIRST_MVC_PAIR width=%d height=%d baseFrameId=%" PRId32
            " mvcFrameId=%" PRId32 " baseHash=%016" PRIx64
            " mvcHash=%016" PRIx64 " distinct=%s\n",
            frame.width_Y,
            frame.height_Y,
            frame.FrameId,
            frame.FrameId_mvc,
            base_hash,
            mvc_hash,
            base_hash != mvc_hash ? "true" : "false");
      }
      if (base_hash != mvc_hash && save_first_pair(&frame, state) != 0) {
        return -1;
      }
    }
  }
  return result == ENOMSG ? 0 : -1;
}

static size_t find_start_code(
    const uint8_t *data, size_t size, size_t from, size_t *prefix_length) {
  for (size_t i = from; i + 3 <= size; i++) {
    if (data[i] == 0 && data[i + 1] == 0) {
      if (data[i + 2] == 1) {
        *prefix_length = 3;
        return i;
      }
      if (i + 4 <= size && data[i + 2] == 0 && data[i + 3] == 1) {
        *prefix_length = 4;
        return i;
      }
    }
  }
  return size;
}

static uint8_t *read_file(const char *path, size_t *size) {
  FILE *file = fopen(path, "rb");
  if (file == NULL) {
    fprintf(stderr, "Could not open %s: %s\n", path, strerror(errno));
    return NULL;
  }
  if (fseek(file, 0, SEEK_END) != 0) {
    fclose(file);
    return NULL;
  }
  long length = ftell(file);
  if (length <= 0 || fseek(file, 0, SEEK_SET) != 0) {
    fclose(file);
    return NULL;
  }
  uint8_t *data = malloc((size_t) length);
  if (data == NULL || fread(data, 1, (size_t) length, file) != (size_t) length) {
    free(data);
    fclose(file);
    return NULL;
  }
  fclose(file);
  *size = (size_t) length;
  return data;
}

int main(int argc, char **argv) {
  if (argc < 2 || argc > 3) {
    fprintf(stderr, "Usage: %s input.264 [output-prefix]\n", argv[0]);
    return 64;
  }
  size_t data_size = 0;
  uint8_t *data = read_file(argv[1], &data_size);
  if (data == NULL) {
    return 65;
  }
  errno = 0;
  Edge264Decoder *decoder =
      edge264_alloc(0, NULL, NULL, 0, NULL, NULL, NULL);
  if (decoder == NULL) {
    fprintf(stderr, "edge264_alloc failed: %s\n", strerror(errno));
    free(data);
    return 66;
  }

  TestState state = {.output_prefix = argc == 3 ? argv[2] : NULL};
  struct timespec started;
  struct timespec finished;
  clock_gettime(CLOCK_MONOTONIC, &started);

  size_t prefix_length = 0;
  size_t prefix = find_start_code(data, data_size, 0, &prefix_length);
  int nal_count = 0;
  while (prefix < data_size) {
    size_t nal_start = prefix + prefix_length;
    size_t next_prefix_length = 0;
    size_t next_prefix =
        find_start_code(data, data_size, nal_start, &next_prefix_length);
    size_t nal_end = next_prefix < data_size ? next_prefix : data_size;
    if (nal_end > nal_start) {
      int result;
      do {
        result =
            edge264_decode_NAL(
                decoder, data + nal_start, data + nal_end, NULL, NULL);
        if (drain_frames(decoder, &state) != 0) {
          fprintf(stderr, "edge264_get_frame failed\n");
          edge264_free(&decoder);
          free(data);
          return 67;
        }
      } while (result == ENOBUFS);
      if (result == ENOTSUP || result == EBADMSG) {
        state.decode_warnings++;
      } else if (result != 0) {
        fprintf(
            stderr,
            "edge264_decode_NAL failed at NAL %d: %s (%d)\n",
            nal_count,
            strerror(result),
            result);
        edge264_free(&decoder);
        free(data);
        return 68;
      }
      nal_count++;
    }
    prefix = next_prefix;
    prefix_length = next_prefix_length;
  }

  uint8_t end_marker = 0;
  int flush_result;
  do {
    flush_result =
        edge264_decode_NAL(
            decoder, &end_marker, &end_marker, NULL, NULL);
    if (drain_frames(decoder, &state) != 0) {
      fprintf(stderr, "edge264_get_frame failed while flushing\n");
      edge264_free(&decoder);
      free(data);
      return 69;
    }
  } while (flush_result == ENOBUFS);

  clock_gettime(CLOCK_MONOTONIC, &finished);
  double elapsed_ms =
      (finished.tv_sec - started.tv_sec) * 1000.0
          + (finished.tv_nsec - started.tv_nsec) / 1000000.0;
  printf(
      "RESULT nals=%d frames=%d mvcFrames=%d distinctMvcFrames=%d warnings=%d"
      " elapsedMs=%.3f pairImages=%s\n",
      nal_count,
      state.frames,
      state.mvc_frames,
      state.distinct_mvc_frames,
      state.decode_warnings,
      elapsed_ms,
      state.wrote_pair ? "true" : "false");

  edge264_free(&decoder);
  free(data);
  if (state.mvc_frames == 0 || state.distinct_mvc_frames == 0) {
    return 2;
  }
  return 0;
}

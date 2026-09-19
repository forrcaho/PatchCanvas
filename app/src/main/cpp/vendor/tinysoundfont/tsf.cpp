// The implementation half of the single-header library, compiled once and on its own so
// it is held to upstream's warnings rather than ours -- as DaisySP is.
#define TSF_IMPLEMENTATION
#include "tsf.h"

// Ours, not upstream's: a preset's bank and program number. The public API can look a
// preset up by them but not report them, and the structs that hold them are visible only
// in this translation unit.
extern "C" int tsf_preset_bank(const tsf *f, int preset) {
    return preset < 0 || preset >= f->presetNum ? -1 : f->presets[preset].bank;
}

extern "C" int tsf_preset_number(const tsf *f, int preset) {
    return preset < 0 || preset >= f->presetNum ? -1 : f->presets[preset].preset;
}

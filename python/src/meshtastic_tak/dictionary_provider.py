"""Loads and provides zstd compression dictionaries."""

from __future__ import annotations

import sys

if sys.version_info >= (3, 11):
    from importlib.resources import files
else:
    from importlib.resources import files  # type: ignore[attr-defined]
    # importlib.resources.files() is available from 3.9+ via importlib_resources
    # backport, but it was added to the stdlib in 3.9 as well (though the
    # Traversable API was refined in 3.11).  For 3.9/3.10 the stdlib version
    # works for our simple use-case (reading a file from a sub-package).

from .cot_type_mapper import CotTypeMapper, COTTYPE_OTHER

DICT_ID_NON_AIRCRAFT = 0
DICT_ID_AIRCRAFT = 1
DICT_ID_UNCOMPRESSED = 0xFF


def _load_dict(name: str) -> bytes:
    return (
        files("meshtastic_tak.resources")
        .joinpath(name)
        .read_bytes()
    )


_non_aircraft_dict: bytes | None = None
_aircraft_dict: bytes | None = None


class DictionaryProvider:
    @staticmethod
    def non_aircraft_dict() -> bytes:
        global _non_aircraft_dict
        if _non_aircraft_dict is None:
            _non_aircraft_dict = _load_dict("dict_non_aircraft.zstd")
        return _non_aircraft_dict

    @staticmethod
    def aircraft_dict() -> bytes:
        global _aircraft_dict
        if _aircraft_dict is None:
            _aircraft_dict = _load_dict("dict_aircraft.zstd")
        return _aircraft_dict

    @staticmethod
    def get_dictionary(dict_id: int) -> bytes | None:
        if dict_id == DICT_ID_NON_AIRCRAFT:
            return DictionaryProvider.non_aircraft_dict()
        elif dict_id == DICT_ID_AIRCRAFT:
            return DictionaryProvider.aircraft_dict()
        return None

    @staticmethod
    def select_dict_id(cot_type_id: int, cot_type_str: str | None = None) -> int:
        if cot_type_id != COTTYPE_OTHER:
            return DICT_ID_AIRCRAFT if CotTypeMapper.is_aircraft(cot_type_id) else DICT_ID_NON_AIRCRAFT
        if cot_type_str and CotTypeMapper.is_aircraft_string(cot_type_str):
            return DICT_ID_AIRCRAFT
        return DICT_ID_NON_AIRCRAFT

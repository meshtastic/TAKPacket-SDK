"""Loads and provides zstd compression dictionaries."""

import os
from .cot_type_mapper import CotTypeMapper, COTTYPE_OTHER

DICT_ID_NON_AIRCRAFT = 0
DICT_ID_AIRCRAFT = 1
DICT_ID_UNCOMPRESSED = 0xFF

_RESOURCES_DIR = os.path.join(os.path.dirname(__file__), "..", "..", "resources")


def _load_dict(name: str) -> bytes:
    path = os.path.join(_RESOURCES_DIR, name)
    with open(path, "rb") as f:
        return f.read()


_non_aircraft_dict = None
_aircraft_dict = None


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
    def get_dictionary(dict_id: int) -> "bytes | None":
        if dict_id == DICT_ID_NON_AIRCRAFT:
            return DictionaryProvider.non_aircraft_dict()
        elif dict_id == DICT_ID_AIRCRAFT:
            return DictionaryProvider.aircraft_dict()
        return None

    @staticmethod
    def select_dict_id(cot_type_id: int, cot_type_str: "str | None" = None) -> int:
        if cot_type_id != COTTYPE_OTHER:
            return DICT_ID_AIRCRAFT if CotTypeMapper.is_aircraft(cot_type_id) else DICT_ID_NON_AIRCRAFT
        if cot_type_str and CotTypeMapper.is_aircraft_string(cot_type_str):
            return DICT_ID_AIRCRAFT
        return DICT_ID_NON_AIRCRAFT

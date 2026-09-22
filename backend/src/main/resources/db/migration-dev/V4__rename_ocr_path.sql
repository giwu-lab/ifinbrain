-- V4: rename OCR menu path to /system/customurl for existing installations.
UPDATE sys_menu SET path = '/system/customurl' WHERE id = 25 AND path = '/system/ocr';
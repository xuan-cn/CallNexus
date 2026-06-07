ALTER TABLE cc_form_field
    ADD COLUMN layout_span INT NOT NULL DEFAULT 12 COMMENT '表单栅格宽度：12半行，24整行' AFTER sort_order;

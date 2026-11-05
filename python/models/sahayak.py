# =============================================================================
# Pension Sahayak. Plan sections 6.4, 9 and 10.
#
# The feature is only worth having if it is *cited*. So the schema makes an
# uncited answer impossible to represent: an answer is assembled from chunks,
# and a chunk has a document, and a document has a status. Only `current` is
# ever shown, and `superseded` is quoted only for a question about a past
# period.
#
# The question log stores a HASH of the user id, never the id. Plan section 14:
# these questions are a pensioner asking about their own arrears, and the log
# exists to improve answers rather than to build a file on somebody's finances.
# =============================================================================
from __future__ import annotations

from sqlalchemy import (
    Boolean,
    Float,
    ForeignKey,
    Index,
    Integer,
    String,
    Text,
    TypeDecorator,
    UniqueConstraint,
)
from sqlalchemy.orm import Mapped, mapped_column

from python.models.base import Base, JsonType, now


class Embedding(TypeDecorator):
    """
    A 1024-dimension float vector.

    Rendered as pgvector's `vector(1024)` on Postgres, and as plain JSON on
    SQLite, so the whole suite still runs with no server. The dimensionality is
    fixed in the column definition and not in the data, which is what stops a
    768-dimension model being quietly loaded into a 1024 column.
    """

    impl = JsonType
    cache_ok = True
    DIM = 1024

    def load_dialect_impl(self, dialect):
        if dialect.name == "postgresql":
            from pgvector.sqlalchemy import Vector

            return dialect.type_descriptor(Vector(self.DIM))
        return dialect.type_descriptor(JsonType)


class SahayakDoc(Base):
    """
    One official document: a DoPPW OM, the CCS (Pension) Rules, a Jeevan
    Pramaan circular.

    `status` is the safety mechanism. A document is never edited into a new
    one; a correction is a new row that supersedes the old, so an answer given
    last year can still be traced and re-checked.
    """

    __tablename__ = "sahayak_docs"

    id: Mapped[int] = mapped_column(Integer, primary_key=True, autoincrement=True)
    title: Mapped[str] = mapped_column(String(300), index=True)
    issuer: Mapped[str] = mapped_column(String(120), index=True)
    doc_no: Mapped[str | None] = mapped_column(String(80), index=True)
    url: Mapped[str | None] = mapped_column(String(500))
    issued_on: Mapped[float | None] = mapped_column(Float)
    effective_on: Mapped[float | None] = mapped_column(Float, index=True)
    status: Mapped[str] = mapped_column(String(16), default="draft", index=True)
    superseded_by_id: Mapped[int | None] = mapped_column(
        ForeignKey("sahayak_docs.id", ondelete="SET NULL")
    )
    # Set once the admin has read the extracted text and approved publication.
    # The sahayak_crawl job writes drafts; nothing reaches a user without this.
    approved_at: Mapped[float | None] = mapped_column(Float, index=True)
    # Which services the document applies to; copied onto every chunk at index
    # time. Empty means it applies to everyone (an income-tax circular).
    service_types: Mapped[list] = mapped_column(JsonType, default=list)
    created_at: Mapped[float] = mapped_column(Float, default=now)


class SahayakChunk(Base):
    """
    A 200–600 token passage, the unit that is actually retrieved.

    `service_types` empty means "applies to every service". An arrears passage
    for a railway pensioner is not the CCS rule, and filtering here is what
    stops the Sahayak answering the wrong service's question confidently.
    """

    __tablename__ = "sahayak_chunks"

    id: Mapped[int] = mapped_column(Integer, primary_key=True, autoincrement=True)
    doc_id: Mapped[int] = mapped_column(
        ForeignKey("sahayak_docs.id", ondelete="CASCADE"), index=True
    )
    # "PART IV > Rule 12 > Table C" — shown so a user can find the page.
    heading_path: Mapped[str] = mapped_column(String(300))
    text: Mapped[str] = mapped_column(Text)
    page: Mapped[int | None] = mapped_column(Integer)
    service_types: Mapped[list] = mapped_column(JsonType, default=list)
    # Only superseded documents chunk for a past period, so the answer can say
    # "this was the rule then, it has since been replaced".
    applies_from: Mapped[float | None] = mapped_column(Float)
    applies_to: Mapped[float | None] = mapped_column(Float)
    embedding: Mapped[list | None] = mapped_column(Embedding, nullable=True)
    # A full-text index over the passage, which is what retrieval actually
    # uses. The vector is a second pass, not the first.
    tsv: Mapped[str | None] = mapped_column(Text)

    __table_args__ = (Index("ix_chunks_doc_page", "doc_id", "page"),)


class SahayakQuestion(Base):
    __tablename__ = "sahayak_questions"

    id: Mapped[int] = mapped_column(Integer, primary_key=True, autoincrement=True)
    # A one-way digest of the user id. Never the id itself.
    user_hash: Mapped[str | None] = mapped_column(String(64), index=True)
    lang: Mapped[str] = mapped_column(String(12), default="en")
    question: Mapped[str] = mapped_column(Text)
    intent: Mapped[str] = mapped_column(String(24), default="unknown", index=True)
    chunk_ids: Mapped[list] = mapped_column(JsonType, default=list)
    answer: Mapped[str | None] = mapped_column(Text)
    model: Mapped[str | None] = mapped_column(String(64))
    # Below SAHAYAK_MIN_CONFIDENCE the answer is not published and the question
    # is routed to a volunteer instead.
    confidence: Mapped[float] = mapped_column(Float, default=0.0, index=True)
    feedback: Mapped[str | None] = mapped_column(String(16), index=True)
    asked_at: Mapped[float] = mapped_column(Float, default=now, index=True)
    # Plan section 14: the log is pruned on this schedule.
    expires_at: Mapped[float | None] = mapped_column(Float, index=True)


class VolunteerTicket(Base):
    """
    A question a volunteer picks up, and the route to a human.

    A ticket is created for every question the corpus could not answer, and for
    every "ask a volunteer" tap. A good answer is promoted into the offline FAQ
    only once it carries a source — otherwise the offline corpus fills with
    whatever a well-meaning volunteer believed.
    """

    __tablename__ = "volunteer_tickets"

    id: Mapped[int] = mapped_column(Integer, primary_key=True, autoincrement=True)
    question_id: Mapped[int] = mapped_column(
        ForeignKey("sahayak_questions.id", ondelete="CASCADE"), index=True
    )
    assigned_to: Mapped[int | None] = mapped_column(
        ForeignKey("users.id", ondelete="SET NULL"), index=True
    )
    answer: Mapped[str | None] = mapped_column(Text)
    status: Mapped[str] = mapped_column(String(16), default="open", index=True)
    promoted_faq_id: Mapped[int | None] = mapped_column(Integer)
    created_at: Mapped[float] = mapped_column(Float, default=now, index=True)
    answered_at: Mapped[float | None] = mapped_column(Float)
    # Set ONLY when the person explicitly asked for a volunteer, so they can be
    # told the answer. Cleared the moment the answer is delivered. Auto-created
    # tickets (a question the corpus could not answer) never carry it: the
    # question log is stored against a hash precisely so it is not a file on a
    # person (models docstring above).
    asker_id: Mapped[int | None] = mapped_column(
        ForeignKey("users.id", ondelete="SET NULL"), index=True
    )
    source_doc_id: Mapped[int | None] = mapped_column(
        ForeignKey("sahayak_docs.id", ondelete="SET NULL")
    )


class OfflineFaq(Base):
    """
    The bundled corpus, so Sahayak is not an empty box on a train.

    Bundled rather than fetched: a pensioner in a village asking about a
    life certificate is exactly the case the feature exists for, and it is
    exactly the case where there is no signal.
    """

    __tablename__ = "offline_faq"

    id: Mapped[int] = mapped_column(Integer, primary_key=True, autoincrement=True)
    question: Mapped[str] = mapped_column(Text)
    answer: Mapped[str] = mapped_column(Text)
    lang: Mapped[str] = mapped_column(String(12), default="en")
    ask_count: Mapped[int] = mapped_column(Integer, default=0, index=True)
    updated_at: Mapped[float] = mapped_column(Float, default=now)


class DrRate(Base):
    """
    A dearness allowance rate, as an order fixes it.

    Never edited. A correction is a new row with a later `effective_from`, so
    an arrears figure calculated last year still reproduces today — which is
    the difference between a calculator and a slot machine.
    """

    __tablename__ = "dr_rates"

    id: Mapped[int] = mapped_column(Integer, primary_key=True, autoincrement=True)
    service_type: Mapped[str] = mapped_column(String(32), index=True)
    rate_percent: Mapped[float] = mapped_column(Float)
    effective_from: Mapped[float] = mapped_column(Float, index=True)
    source_doc_id: Mapped[int | None] = mapped_column(
        ForeignKey("sahayak_docs.id", ondelete="SET NULL")
    )

    __table_args__ = (
        # One rate per service per date. The arrears walk relies on this.
        UniqueConstraint("service_type", "effective_from", name="uq_dr_rate"),
    )


class CommutationTable(Base):
    """
    A commutation factor table.

    Also never edited, for the same reason as `dr_rates`. The table changes by
    order and the version that applied at retirement is the version that
    decides the figure.
    """

    __tablename__ = "commutation_tables"

    id: Mapped[int] = mapped_column(Integer, primary_key=True, autoincrement=True)
    service_type: Mapped[str] = mapped_column(String(32), index=True)
    # Years of commuted pension bought per year of service.
    service_years: Mapped[float] = mapped_column(Float)
    factor: Mapped[float] = mapped_column(Float)
    effective_from: Mapped[float] = mapped_column(Float, index=True)
    source_doc_id: Mapped[int | None] = mapped_column(
        ForeignKey("sahayak_docs.id", ondelete="SET NULL")
    )

    __table_args__ = (
        UniqueConstraint("service_type", "service_years", "effective_from", name="uq_comm_table"),
    )


class EvaluationRun(Base):
    """
    One run of the retrieval evaluation, after every ingest.

    The plan's rule is that a corpus is published only if its scores hold. This
    is where "held" is recorded, so a regression in the index is visible as a
    failed row rather than as users quietly getting worse answers.
    """

    __tablename__ = "evaluation_runs"

    id: Mapped[int] = mapped_column(Integer, primary_key=True, autoincrement=True)
    ran_at: Mapped[float] = mapped_column(Float, default=now, index=True)
    # Mean reciprocal rank over the held-out question set.
    mrr: Mapped[float] = mapped_column(Float)
    # Share of questions answered with at least one current source.
    citation_rate: Mapped[float] = mapped_column(Float)
    passed: Mapped[bool] = mapped_column(Boolean, default=False, index=True)
    notes: Mapped[str | None] = mapped_column(Text)

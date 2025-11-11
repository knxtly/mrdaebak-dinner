(function () {
    const menuSelect = document.getElementById("menuSelect");
    const menuCards = document.querySelectorAll(".menu-card");

    const voiceBtn = document.getElementById("voiceBtn");
    const voiceInput = document.getElementById("voiceText");
    const voiceForm = document.getElementById("voiceForm");
    const voiceMessage = document.getElementById("voiceMessage");
    const chatContainer = document.getElementById("chatContainer");


    let recognition = null;
    let recognizing = false;
    let interimTranscript = "";

    // 아이템 input들을 객체로 캐싱
    const items = {
        wine: document.getElementById("wine"),
        steak: document.getElementById("steak"),
        coffee_cup: document.getElementById("coffee_cup"),
        coffee_pot: document.getElementById("coffee_pot"),
        salad: document.getElementById("salad"),
        eggscramble: document.getElementById("eggscramble"),
        bacon: document.getElementById("bacon"),
        bread: document.getElementById("bread"),
        baguette: document.getElementById("baguette"),
        champagne: document.getElementById("champagne"),
    };

    // 모든 스타일 available, check 해제
    function resetStyle() {
        document
            .querySelectorAll('input[name="dinnerStyle"]')
            .forEach((radio) => {
                radio.disabled = false;
                radio.checked = false;
            });
    }

    // 모든 아이템 수량 0으로 초기화하는 함수
    function resetItems() {
        Object.values(items).forEach((input) => (input.value = 0));
    }

    // 메뉴 선택 시 스타일/아이템 초기화 및 기본값 설정
    function updateMenuSelection(selectedValue, applyDefaultItems) {
        // 1. 스타일 옵션과 값 초기화
        resetStyle();
        document.querySelector("#styleSimple").checked = true; // 'Simple'을 기본으로 선택

        // 2. CHAMPAGNE 메뉴의 특수 로직: SIMPLE 스타일 비활성화
        if (selectedValue === "CHAMPAGNE") {
            document.querySelector("#styleSimple").disabled = true;
            document.querySelector("#styleGrand").checked = true;
        }

        // 3. 아이템 초기화 및 기본값 설정
        if (applyDefaultItems) {
            // 모든 아이템 수량 초기화
            resetItems();

            // 메뉴별 기본 아이템 값 설정
            switch (selectedValue) {
                case "VALENTINE":
                    items.wine.value = 1;
                    items.steak.value = 1;
                    break;
                case "FRENCH":
                    items.coffee_cup.value = 1;
                    items.wine.value = 1;
                    items.salad.value = 1;
                    items.steak.value = 1;
                    break;
                case "ENGLISH":
                    items.eggscramble.value = 1;
                    items.bacon.value = 1;
                    items.bread.value = 1;
                    items.steak.value = 1;
                    break;
                case "CHAMPAGNE":
                    items.champagne.value = 1;
                    items.baguette.value = 4;
                    items.coffee_pot.value = 1;
                    items.wine.value = 1;
                    items.steak.value = 1;
            }
        }
    }

    // 메뉴 선택 함수
    function selectMenu(menuValue, applyDefaultItems) {
        if (!menuValue) return; // 메뉴 값이 없으면 아무것도 하지 않음

        const upperMenuValue = menuValue.toUpperCase();

        // 1. 시각적 활성화/비활성화
        menuCards.forEach((card) => {
            if (card.dataset.menuValue === upperMenuValue) {
                card.classList.add("selected");
            } else {
                card.classList.remove("selected");
            }
        });

        // 2. <select> 값 업데이트 (폼 제출을 위해 필수)
        menuSelect.value = upperMenuValue;

        // 3. 스타일/아이템 초기화 및 기본값 설정
        updateMenuSelection(upperMenuValue, applyDefaultItems);
    }

    // 메뉴 카드 클릭 이벤트 핸들러
    menuCards.forEach((card) => {
        card.addEventListener("click", () => {
            const menuValue = card.getAttribute("data-menu-value");
            selectMenu(menuValue, true); // 메뉴 선택 함수 호출 & default item구성 반영
        });
    });

    // 음성 인식 버튼
    voiceBtn.addEventListener("click", () => {
        const SpeechRecognition =
            window.SpeechRecognition || window.webkitSpeechRecognition;
        if (!SpeechRecognition) {
            alert("브라우저가 음성인식을 지원하지 않습니다.");
            return;
        }
        if (recognizing) {
            recognition.stop();
            recognizing = false;
            return;
        }

        recognition = new SpeechRecognition();
        recognition.lang = "ko-KR";
        recognition.interimResults = true;
        recognizing = true;
        interimTranscript = "";
        const originalText = "음성 인식";
        voiceBtn.textContent = "말하는 중... (클릭 시 종료)";
        recognition.start();

        recognition.onresult = (event) => {
            let finalTranscript = "";
            for (let i = event.resultIndex; i < event.results.length; ++i) {
                if (event.results[i].isFinal)
                    finalTranscript += event.results[i][0].transcript;
                else interimTranscript = event.results[i][0].transcript;
            }
            voiceInput.value = finalTranscript || interimTranscript;
        };

        recognition.onend = () => {
            recognizing = false;
            voiceBtn.textContent = originalText;
            if (interimTranscript && !voiceInput.value)
                voiceInput.value = interimTranscript;
            voiceMessage.textContent =
                "인식 종료. 필요 시 텍스트를 수정 후 보내기 버튼을 누르세요.";
        };
    });

    // 보내기 버튼 (AI → JSON → Form 반영)
    voiceForm.addEventListener("submit", async (e) => {
        e.preventDefault();
        const userInput = voiceInput.value.trim();
        if (!userInput) {
            voiceMessage.textContent = "텍스트가 비어 있습니다.";
            return;
        }
        voiceMessage.textContent = "답변 생성 중...";

        // ... voiceForm submit 이벤트 리스너 내부 try 블록
        try {
            const response = await fetch("/customer/ai-chat-order", {
                method: "POST",
                headers: { "Content-Type": "application/json" },
                body: JSON.stringify({ userInput }),
            });
            const data = await response.json();

            // data.status === "CONTINUE"
            if (data.status === "CONTINUE") {
                // 사용자 입력 추가
                const userMsg = document.createElement("div");
                userMsg.classList.add("chat-message", "user-message");
                userMsg.textContent = userInput;
                chatContainer.appendChild(userMsg);

                // AI 답변 추가
                const botMsg = document.createElement("div");
                botMsg.classList.add("chat-message", "bot-message");
                botMsg.textContent = data.message;
                chatContainer.appendChild(botMsg);

                // 스크롤 항상 하단으로
                chatContainer.scrollTop = chatContainer.scrollHeight;

                // 다음 입력 준비
                voiceInput.value = "";
                voiceMessage.textContent = "답변 완료";
                return data.message;
            }

            // data.status === "DONE"
            else if (data.status === "DONE") {
                // JSON => menu, style 반영
                if (data.menu) {
                    selectMenu(data.menu);
                }
                if (data.style) {
                    const styleRadio = document.querySelector(
                        `input[name="dinnerStyle"][value="${data.style.toUpperCase()}"]`,
                    );
                    if (styleRadio) styleRadio.checked = true;
                }

                // JSON => item 반영
                if (data.items && typeof data.items === "object") {
                    Object.keys(data.items).forEach((key) => {
                        if (items[key]) {
                            // 우리 item 목록에 있는 키일 경우에만
                            let val = parseInt(data.items[key]);
                            items[key].value = isNaN(val) || val < 0 ? 0 : val;
                        }
                    });
                }

                // JSON => deliveryAddress, cardNumber 반영
                document.getElementById("deliveryAddress").value =
                    data.deliveryAddress || "";
                document.getElementById("cardNumber").value = data.cardNumber || "";

                voiceMessage.textContent = "주문 폼이 자동으로 채워졌습니다.";
            }

            // data.status === "error"
            else {
                voiceMessage.textContent = "Error: " + data.message;
                return;
            }
        } catch (err) {
            voiceMessage.textContent = "오류: " + err.message;
        }
    });

    // 페이지 로드 시 기존 선택된 메뉴를 복원
    document.addEventListener("DOMContentLoaded", () => {
        // initiallySelectedMenu 다시 선택 & default item구성 반영 X
        selectMenu(menuSelect.value, false);
    });
})();

async (page) => {
  await page.addInitScript(() => {
    class MockEventSource extends EventTarget {
      constructor(url) {
        super()
        this.url = String(url)
        this.readyState = 0
        setTimeout(() => {
          this.readyState = 1
          this.dispatchEvent(new Event('open'))
        }, 20)
      }

      close() {
        this.readyState = 2
      }
    }

    MockEventSource.CONNECTING = 0
    MockEventSource.OPEN = 1
    MockEventSource.CLOSED = 2
    window.EventSource = MockEventSource
  })

  const candidates = [
    {
      id: 'reply-1',
      senderName: '小纸船',
      candidateText: '晚上好，今天也辛苦啦。',
    },
    {
      id: 'reply-2',
      senderName: '薄荷汽水',
      candidateText: '当然可以，我们就从你最想聊的那件小事开始。',
    },
    {
      id: 'reply-3',
      senderName: '这是一个特别特别长的B站用户名',
      candidateText: '收到你的问题了，先给我一小会儿，我会认真想清楚再回答。',
    },
    {
      id: 'reply-4',
      senderName: '星星掉进杯子里',
      candidateText: '这个想法很有意思，而且真的可以试试看。',
    },
    {
      id: 'reply-5',
      senderName: '等一封回信',
      candidateText: '我觉得你不必急着一次把所有事都做完。先挑最重要的一小步，做完后看看结果，再决定下一步。这样不但压力更小，也更容易知道哪里需要调整。如果中途犹豫，就回来告诉我你卡在哪里，我们一起拆开它。',
    },
  ]

  await page.route('**/api/overlay/recent', route => route.fulfill({
    status: 200,
    contentType: 'application/json',
    body: JSON.stringify(candidates),
  }))

  await page.reload({ waitUntil: 'networkidle' })
  await page.waitForTimeout(300)
}
